package fi.vm.sade.hakurekisteri.integration.ytl

import akka.actor._
import java.util.UUID
import scala.xml.{XML, Node, Elem}
import fi.vm.sade.hakurekisteri.suoritus.{VirallinenSuoritus, yksilollistaminen, Suoritus}
import fi.vm.sade.hakurekisteri.arvosana.{ArvosanaQuery, Arvosana}
import scala.concurrent.{ExecutionContext, Future}
import akka.pattern.ask
import akka.util.Timeout
import java.util.concurrent.{Executors, TimeUnit}
import org.joda.time._
import fi.vm.sade.hakurekisteri.storage.Identified
import fr.janalyse.ssh.{SSHPassword, SSH}
import scala.concurrent.duration._
import fi.vm.sade.hakurekisteri.arvosana.ArvioYo
import fi.vm.sade.hakurekisteri.integration.henkilo.HenkiloResponse
import fi.vm.sade.hakurekisteri.integration.henkilo.HetuQuery
import scala.util.Failure
import scala.util.Success
import akka.actor.ActorIdentity
import akka.actor.Identify
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.integration.hakemus.{FullHakemus, HakemusQuery}
import java.io.IOException
import fi.vm.sade.hakurekisteri.integration.ytl.YTLXml.Aine


case class YtlReport(waitingforAnswers: Seq[Batch[KokelasRequest]], nextSend: Option[DateTime])

object Report

class YtlActor(henkiloActor: ActorRef, suoritusRekisteri: ActorRef, arvosanaRekisteri: ActorRef, hakemukset: ActorRef, config: Option[YTLConfig]) extends Actor with ActorLogging {
  implicit val ec = context.dispatcher

  var haut = Set[String]()
  var sent = Seq[Batch[KokelasRequest]]()

  val sendTicker = context.system.scheduler.schedule(1.millisecond, 1.minutes, self, CheckSend)
  val pollTicker = context.system.scheduler.schedule(1.minutes, 1.minutes, self, Poll)

  var nextSend: Option[DateTime] = nextSendTime

  val sftpSendContext  =  ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

  val sftpPollContext =  ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

  def nextSendTime: Option[DateTime] = {
    val times = config.map(_.sendTimes).filter(!_.isEmpty)
    Timer.countNextSend(times)
  }

  if (config.isEmpty) log.warning("Starting ytlActor without config")

  var kokelaat = Map[String, Kokelas]()
  var suoritusKokelaat = Map[UUID, (Suoritus with Identified[UUID], Kokelas)]()

  def getNewBatch(haut: Set[String]) = {

    implicit val to: Timeout = 300.seconds
    val haetaan: Set[Future[Seq[FullHakemus]]] = for (
      haku <- haut
    ) yield (hakemukset ? HakemusQuery(Some(haku), None, None)).mapTo[Seq[FullHakemus]].
        recoverWith{case t: Throwable => Future.failed(new HakuException(s"failed to find applications for applicationID $haku", haku, t))}


    Future.sequence(haetaan).map(
      _.toSeq.flatten.
        map((hakemus) => (hakemus.personOid, hakemus.hetu)).
        collect{case (Some(oid), Some(hetu)) => KokelasRequest(oid, hetu)}).
      map((rs) => Batch(items = rs.toSet))


  }

  override def receive: Actor.Receive = {
    case HakuList(current) => haut = current
    case Report => sender ! YtlReport(sent, nextSend)
    case CheckSend if nextSend.getOrElse(DateTime.now.plusDays(1)).isBefore(DateTime.now()) =>
      self ! Send
      nextSend = nextSendTime
    case Send if config.isDefined  =>
      val result = for (
        b <- getNewBatch(haut);
        sent <- send(b)
      ) yield SentBatch(sent)
      result pipeTo self
    case SentBatch(batch) =>
      sent = batch +: sent
    case akka.actor.Status.Failure(h: HakuException) =>
      log.info(s"retrying after haku exception ${h.getCause.getMessage}")
      self ! Send
    case akka.actor.Status.Failure(h: FtpException) =>
      log.info(s"retrying after FTP exception: ${h.getCause.getMessage}")
      self ! Send
    case Poll if config.isDefined  => poll(sent)
    case YtlResult(id, data) if config.isDefined  =>
      val requested = sent.find(_.id == id)
      sent = sent.filterNot(_.id == id)
      handleResponse(requested, data)
    case k: Kokelas if config.isDefined  =>
      log.debug(s"sending ytl data for ${k.oid} yo: ${k.yo} lukio: ${k.lukio}")
      suoritusRekisteri ! k.yo
      kokelaat = kokelaat + (k.oid -> k)
      k.lukio foreach (suoritusRekisteri ! _)
    case s: VirallinenSuoritus with Identified[UUID] if s.komo == YTLXml.yotutkinto && config.isDefined =>
      for (
        kokelas <- kokelaat.get(s.henkiloOid)
      ) {
        context.actorSelection(s.id.toString) ! Identify(s.id)
        kokelaat = kokelaat - kokelas.oid
        suoritusKokelaat = suoritusKokelaat + (s.id -> (s, kokelas))
      }

    case ActorIdentity(id: UUID, Some(ref)) if config.isDefined  => for (
      (suoritus, kokelas) <- suoritusKokelaat.get(id)
    ) {
      ref ! kokelas
      suoritusKokelaat = suoritusKokelaat - id
    }

    case ActorIdentity(id: UUID, None) if config.isDefined => try {
      for (
        (suoritus, kokelas) <- suoritusKokelaat.get(id)
      ) {
        context.actorOf(Props(new ArvosanaUpdateActor(suoritus, kokelas.yoTodistus, arvosanaRekisteri)), id.toString)
        suoritusKokelaat = suoritusKokelaat - id
      }

    } catch {
      case t: Throwable =>
        context.actorSelection(id.toString) ! Identify(id)
        log.warning(s"problem creating arvosana update for ${id.toString} retrying search", t)
    }
  }

  def batchMessage(batch: Batch[KokelasRequest]) =
    <Haku id={batch.id.toString}>
      {for (kokelas <- batch.items) yield <Hetu>{kokelas.hetu}</Hetu>}
    </Haku>

  def uploadFile(batch:Batch[KokelasRequest], localStore: String): (String, String) = {
    val xml = batchMessage(batch)
    val fileName = s"siirto${batch.id.toString}.xml"
    val filePath = s"$localStore/$fileName"
    XML.save(filePath, xml,"ISO-8859-1", xmlDecl = true, doctype = null)
    (fileName, filePath)
  }

  def send(batch: Batch[KokelasRequest]) = config match {
    case Some(YTLConfig(host:String, username: String, password: String, inbox: String, outbox: String, _, localStore)) =>
      Future {
        val (filename, localCopy) = uploadFile(batch, localStore)
        SSH.ftp(host = host, username =username, password = SSHPassword(Some(password))){
          (sftp) =>
            sftp.send(localCopy, s"$inbox/$filename")
        }
        batch
      }(sftpSendContext).recoverWith{
        case t:Throwable => Future.failed(FtpException(t))
      }

    case None => log.warning("sending files to YTL called without config")
      Future.failed(new NoSuchElementException("No Config"))
  }

  def poll(batches: Seq[Batch[KokelasRequest]]): Unit = config match {
    case Some(YTLConfig(host:String, username: String, password: String, inbox: String, outbox: String, _, localStore)) =>
      Future {
        SSH.ftp(host = host, username =username, password = SSHPassword(Some(password))){
          (sftp) =>
            for (
              batch <- batches
            ) {

              val filename = s"outsiirto${batch.id.toString}.xml"
              val path: String = s"$outbox/$filename"
              log.debug(s"polling for $path")
              for (
                result <- sftp.get(path)
              ) {
                sftp.receive(path, s"$localStore/$filename")
                val response = XML.loadString(result)
                self ! YtlResult(batch.id, response)

              }
            }
        }
      }(sftpPollContext)
    case None => log.warning("polling of files from YTL called without config")
  }

  def handleResponse(requested: Option[Batch[KokelasRequest]], data: Elem) = {
    def batch2Finder(batch:Batch[KokelasRequest])(hetu:String):Future[String] = {
      val hetuMap = batch.items.map{case KokelasRequest(oid, kokelasHetu) => kokelasHetu -> oid}.toMap
      hetuMap.get(hetu).map(Future.successful).getOrElse(Future.failed(new NoSuchElementException("can't find oid for hetu in requested data")))
    }

    val finder = requested.map(batch2Finder).getOrElse(resolveOidFromHenkiloPalvelu _)

    import YTLXml._

    val kokelaat = parseKokelaat(data, finder)

    import akka.pattern.pipe

    for (
      kokelas <- kokelaat
    ) kokelas pipeTo self

    Future.sequence(kokelaat).onComplete{
      case Success(parsed) if requested.isDefined =>
        val batch = requested.get
        val found = parsed.map(_.oid).toSet
        val missing = batch.items.map(_.oid).toSet -- found
        for (problem <- missing) log.warning(s"Missing result from YTL for oid $problem in batch ${batch.id}")
      case Failure(t) if requested.isDefined => log.error(t, s"failure in fetching results for ${requested.get.id}")
      case Failure(t) => log.error(t, "failure fetching results from YTL")
      case _ =>  log.warning("no request in memory for a result from YTL")
    }
  }

  def resolveOidFromHenkiloPalvelu(hetu: String): Future[String] =
  {
    implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)
    (henkiloActor ? HetuQuery(hetu)).mapTo[HenkiloResponse].map(_.hetu).flatMap(
      _.map(Future.successful).getOrElse(Future.failed(new NoSuchElementException("can't find oid for hetu in henkilopalvelu"))))
  }
}

case class Batch[A](id: UUID = UUID.randomUUID(), items: Set[A] = Set[A]()) {
  def +(elem: A): Batch[A] = Batch(this.id, this.items + elem)
}

case class KokelasRequest(oid: String, hetu: String)

case class YtlResult(batch: UUID, data: Elem)

case class Kokelas(oid: String,
                   yo: Suoritus,
                   lukio: Option[Suoritus],
                   yoTodistus: Seq[Koe])

object Send

object Poll

object CheckSend

object CheckPoll

object Timer {


  implicit val dtOrder:Ordering[LocalTime] = new Ordering[LocalTime] {
    override def compare(x: LocalTime, y: LocalTime) = x match {
      case _ if x isEqual y => 0
      case _ if x isAfter y => 1
      case _ if y isAfter x => -1
    }
  }


  def countNextSend(timeConf: Option[Seq[LocalTime]]): Option[DateTime] = {
    timeConf.map {
      (times) =>
        times.sorted.map(_.toDateTimeToday).find((t) => {
          val searchTime: DateTime = DateTime.now
          t.isAfter(searchTime)
        }).getOrElse(times.sorted.head.toDateTimeToday.plusDays(1))
    }
  }
}

object YTLXml {

  case class Aine(aine: String, lisatiedot: String)

  object Aine {

    val aineet = Map("SA" -> Aine("A", "SA"),"EA" -> Aine("A", "EN"),"EB" -> Aine("B", "EN"),"HA" -> Aine("A", "UN"),"FB" -> Aine("B", "RA"),"E2" -> Aine("B", "EN"),"M" -> Aine("PITKA", "MA"),"SC" -> Aine("C", "SA"),"VA" -> Aine("A", "VE"),"F2" -> Aine("B", "RA"),"GB" -> Aine("B", "PG"),"PS" -> Aine("AINEREAALI", "PS"),"I" -> Aine("AI", "IS"),"HI" -> Aine("AINEREAALI", "HI"),"V2" -> Aine("B", "VE"),"RY" -> Aine("REAALI", "ET"),"TA" -> Aine("A", "IT"),"CB" -> Aine("B", "FI"),"CC" -> Aine("C", "FI"),"S9" -> Aine("SAKSALKOUL", "SA"),"G2" -> Aine("B", "PG"),"V1" -> Aine("A", "VE"),"HB" -> Aine("B", "UN"),"TB" -> Aine("B", "IT"),"O" -> Aine("AI", "RU"),"A" -> Aine("AI", "FI"),"P1" -> Aine("A", "ES"),"GC" -> Aine("C", "PG"),"S2" -> Aine("B", "SA"),"PC" -> Aine("C", "ES"),"FY" -> Aine("AINEREAALI", "FY"),"EC" -> Aine("C", "EN"),"L1" -> Aine("D", "LA"),"H1" -> Aine("A", "UN"),"O5" -> Aine("VI2", "RU"),"FA" -> Aine("A", "RA"),"CA" -> Aine("A", "FI"),"F1" -> Aine("A", "RA"),"J" -> Aine("KYPSYYS", "EN"),"A5" -> Aine("VI2", "FI"),"Z" -> Aine("AI", "ZA"),"IC" -> Aine("C", "IS"),"KE" -> Aine("AINEREAALI", "KE"),"T1" -> Aine("A", "IT"),"RO" -> Aine("REAALI", "UO"),"YH" -> Aine("AINEREAALI", "YH"),"BA" -> Aine("A", "RU"),"H2" -> Aine("B", "UN"),"BI" -> Aine("AINEREAALI", "BI"),"VC" -> Aine("C", "VE"),"FF" -> Aine("AINEREAALI", "FF"),"BB" -> Aine("B", "RU"),"E1" -> Aine("A", "EN"),"T2" -> Aine("B", "IT"),"DC" -> Aine("C", "ZA"),"GE" -> Aine("AINEREAALI", "GE"),"P2" -> Aine("B", "ES"),"TC" -> Aine("C", "IT"),"G1" -> Aine("A", "PG"),"UO" -> Aine("AINEREAALI", "UO"),"RR" -> Aine("REAALI", "UE"),"VB" -> Aine("B", "VE"),"KC" -> Aine("C", "KR"),"ET" -> Aine("AINEREAALI", "ET"),"PB" -> Aine("B", "ES"),"SB" -> Aine("B", "SA"),"S1" -> Aine("A", "SA"),"QC" -> Aine("C", "QC"),"N" -> Aine("LYHYT", "MA"),"L7" -> Aine("C", "LA"),"PA" -> Aine("A", "ES"),"FC" -> Aine("C", "RA"),"TE" -> Aine("AINEREAALI", "TE"),"GA" -> Aine("A", "PG"),"UE" -> Aine("AINEREAALI", "UE"))

    def apply(koetunnus:String, aineyhdistelmärooli: Option[String] = None):Aine =
      if (aineyhdistelmärooli == Some("22"))
        Aine("TOINENKIELI", aineet(koetunnus).lisatiedot)
      else
        aineet(koetunnus)
  }




  def parseKokelaat(data:Elem, oidFinder: String => Future[String])(implicit ec: ExecutionContext): Seq[Future[Kokelas]] = {
    val kokelaat = data \\ "YLIOPPILAS"
    kokelaat map {
      (kokelas) =>
        val hetu = (kokelas \ "HENKILOTUNNUS").text
        parseKokelas(oidFinder(hetu), kokelas)
    }
  }

  def parseKokelas(oidFuture: Future[String], kokelas: Node)(implicit ec: ExecutionContext): Future[Kokelas] = {
    for {
      oid <- oidFuture
    } yield {
      val yo = extractYo(oid, kokelas)
      Kokelas(oid, yo , extractLukio(oid, kokelas), extractTodistus(yo, kokelas) )
    }
  }

  val YTL: String = "1.2.246.562.10.43628088406"

  val yotutkinto = "1.2.246.562.5.2013061010184237348007"

  object YoTutkinto {
    def apply(suorittaja:String, valmistuminen: LocalDate, kieli:String, valmis: Boolean = true, vahvistettu: Boolean = true) = {
      VirallinenSuoritus(
        komo = yotutkinto,
        myontaja = YTL,
        tila = if (valmis) "VALMIS" else "KESKEN",
        valmistuminen = valmistuminen,
        henkilo = suorittaja,
        yksilollistaminen = yksilollistaminen.Ei,
        suoritusKieli = kieli,
        vahv = vahvistettu,
        lahde = YTL)
    }
  }

  val kevat = "(\\d{4})K".r
  val syksy = "(\\d{4})S".r
  val suoritettu = "suor".r

  val kevaanAlku = new MonthDay(6, 1)

  def parseKausi(kausi: String) = kausi match {
    case kevat(vuosi) => Some(kevaanAlku.toLocalDate(vuosi.toInt))
    case syksy(vuosi) => Some(new MonthDay(12, 21).toLocalDate(vuosi.toInt))
    case _ => None
  }

  def extractYo(oid: String, kokelas: Node): VirallinenSuoritus = {
    val kieli = (kokelas \ "TUTKINTOKIELI").text
    val suoritettu = for (
      valmistuminen <- parseValmistuminen(kokelas)
    ) yield {

      YoTutkinto(suorittaja = oid, valmistuminen = valmistuminen, kieli = kieli)

    }
    suoritettu.getOrElse(
      YoTutkinto(suorittaja = oid, valmistuminen = parseKausi(nextKausi).get, kieli = kieli, valmis = false)
    )
  }


  def nextKausi: String = MonthDay.now() match {
    case d if d.isBefore(kevaanAlku) => s"${LocalDate.now.getYear}K"
    case _ =>  s"${LocalDate.now.getYear}S"

  }


  def parseValmistuminen(kokelas: Node): Option[LocalDate] = {
    val yoSuoritettu = (kokelas \ "YLIOPPILAAKSITULOAIKA").text
    if (yoSuoritettu.isEmpty) None
    else {
      yoSuoritettu match {
        case suoritettu() =>
          val koeTehty = (kokelas \ "TUTKINTOAIKA").text
          parseKausi(koeTehty)
        case _ =>
          parseKausi(yoSuoritettu)
      }
    }
  }

  def extractLukio(oid:String, kokelas:Node): Option[Suoritus] = None

  import org.scalatra.util.RicherString._

  def extractTodistus(yo: Suoritus, kokelas: Node): Seq[Koe] = {
    (kokelas \\ "KOE").map{
      (koe: Node) =>
       val arvio = ArvioYo((koe \ "ARVOSANA").text, (koe \ "YHTEISPISTEMAARA").text.blankOption.map(_.toInt))
        Koe(arvio, (koe \ "KOETUNNUS").text, (koe \ "AINEYHDISTELMAROOLI").text, parseKausi((koe \ "TUTKINTOKERTA").text).get)
    }
  }


}

case class Koe(arvio: ArvioYo, koetunnus: String, aineyhdistelmarooli: String, myonnetty: LocalDate) {

  val aine = Aine(koetunnus, Some(aineyhdistelmarooli))

  val valinnainen = aineyhdistelmarooli.toInt >= 60

  def toArvosana(suoritus: Suoritus with Identified[UUID]) = {
    Arvosana(suoritus.id, arvio, aine.aine: String, Some(aine.lisatiedot), valinnainen: Boolean, Some(myonnetty), YTLXml.YTL)
  }
}


class ArvosanaUpdateActor(suoritus: Suoritus with Identified[UUID], var kokeet: Seq[Koe], arvosanaRekisteri: ActorRef) extends Actor {
  def isKorvaava(old:Arvosana) = (uusi:Arvosana) => uusi.aine == old.aine && uusi.myonnetty == old.myonnetty

  override def receive: Actor.Receive = {
    case s:Seq[_] =>
      fetch.foreach(_.cancel())
      val uudet = kokeet.map(_.toArvosana(suoritus))
      s.map{
        case (a:Arvosana with Identified[UUID]) =>
          val korvaava = uudet.find(isKorvaava(a))
          if (korvaava.isDefined) korvaava.get.identify(a.id)
          else a
      } foreach (arvosanaRekisteri ! _)
      uudet.filterNot((uusi) => s.exists{case old: Arvosana => isKorvaava(old)(uusi)}) foreach (arvosanaRekisteri ! _)
      context.stop(self)
    case Kokelas(_, _, _ , todistus) => kokeet = todistus
  }

  var fetch: Option[Cancellable] = None

  override def preStart(): Unit = {
    implicit val ec = context.dispatcher
     fetch = Some(context.system.scheduler.schedule(1.millisecond, 1.minute, arvosanaRekisteri, ArvosanaQuery(Some(suoritus.id))))
  }
}

case class YTLConfig(host:String, username: String, password: String, inbox: String, outbox: String, sendTimes: Seq[LocalTime], localStore:String)

case class Handled(batches: Seq[UUID])

case class SentBatch(batch: Batch[KokelasRequest])

case class HakuList(haut: Set[String])

case class HakuException(message: String, haku: String, cause: Throwable) extends Exception(message, cause)

case class FtpException(cause:Throwable) extends IOException("problem with SFTP send", cause)

