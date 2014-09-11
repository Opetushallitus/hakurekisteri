package fi.vm.sade.hakurekisteri.integration.ytl

import akka.actor._
import java.util.UUID
import scala.xml.{XML, Node, Elem}
import fi.vm.sade.hakurekisteri.suoritus.{yksilollistaminen, Suoritus}
import fi.vm.sade.hakurekisteri.arvosana.{ArvosanaQuery, Arvosana}
import scala.concurrent.{ExecutionContext, Future}
import akka.event.Logging
import akka.pattern.ask
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import org.joda.time.{LocalTime, DateTime, MonthDay, LocalDate}
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.arvosana.ArvioYo
import fi.vm.sade.hakurekisteri.integration.henkilo.HenkiloResponse
import fi.vm.sade.hakurekisteri.integration.henkilo.HetuQuery
import scala.util.Failure
import scala.Some
import scala.util.Success
import fr.janalyse.ssh.{SSHPassword, SSH}
import java.io.{PrintWriter, ByteArrayOutputStream}
import scala.concurrent.duration._


case class YtlReport(current: Batch[KokelasRequest], waitingforAnswers: Seq[Batch[KokelasRequest]], nextSend: Option[DateTime])

object Report

class YtlActor(henkiloActor: ActorRef, suoritusRekisteri: ActorRef, arvosanaRekisteri: ActorRef, config: Option[YTLConfig]) extends Actor {

  implicit val ec = context.dispatcher

  var batch = Batch[KokelasRequest]()
  var sent = Seq[Batch[KokelasRequest]]()

  val sendTicker = context.system.scheduler.schedule(1.millisecond, 1.minutes, self, CheckSend)
  val pollTicker = context.system.scheduler.schedule(1.minutes, 1.minutes, self, Poll)

  var nextSend: Option[DateTime] = nextSendTime


  def nextSendTime: Option[DateTime] = {
    config.map(_.sendTimes).filter(!_.isEmpty).map{
      (times) =>
        times.map(_.toDateTimeToday).find((t) => {
          val searchTime: DateTime = DateTime.now
          t.isAfter(searchTime)
        }).getOrElse(times.head.toDateTimeToday.plusDays(1))



    }
  }


  val log = Logging(context.system, this)


  var kokelaat = Map[String, Kokelas]()
  var suoritusKokelaat = Map[UUID, (Suoritus with Identified[UUID], Kokelas)]()

  override def receive: Actor.Receive = {
    case Report => sender ! YtlReport(batch, sent, nextSend)
    case CheckSend if nextSend.getOrElse(DateTime.now.plusDays(1)).isBefore(DateTime.now()) =>
      self ! Send
      nextSend = nextSendTime
    case k:KokelasRequest if config.isDefined =>
      batch = k +: batch
    case Send if config.isDefined && !batch.items.isEmpty =>
                 log.debug(s"sending batch ${batch.id} with ${batch.items.size} applicants")
                 send(batch)
                 sent = batch +: sent
                 batch = Batch[KokelasRequest]()
                 log.debug(s"new batch ${batch.id} with ${batch.items.size} applicants")
    case Poll if config.isDefined  => poll(sent)
    case YtlResult(id, data) if config.isDefined  =>
      val requested = sent.find(_.id == id)
      sent = sent.filterNot(_.id == id)
      handleResponse(requested, data)
    case k: Kokelas if config.isDefined  =>
      log.debug(s"sending ytl data for ${k.oid} yo: ${k.yo} lukio: ${k.lukio}")
      k.yo foreach {
        yotutkinto =>
          suoritusRekisteri ! yotutkinto
          kokelaat = kokelaat + (k.oid -> k)
      }
      k.lukio foreach (suoritusRekisteri ! _)
    case s: Suoritus with Identified[UUID] if s.komo == YTLXml.yotutkinto && config.isDefined =>
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



  def uploadFile(message: Elem): Array[Byte] = {
    val os = new ByteArrayOutputStream()
    val writer = new PrintWriter(os)
    try XML.write(writer, message, "ISO-8859-1", xmlDecl = true, doctype = null)
    finally writer.close()
    os.toByteArray
  }

  def send(batch: Batch[KokelasRequest]): Unit = config match {
    case Some(YTLConfig(host:String, username: String, password: String, inbox: String, outbox: String, _)) =>
      SSH.ftp(host = host, username =username, password = SSHPassword(Some(password))){
        (sftp) =>
          sftp.putBytes(uploadFile(batchMessage(batch)), s"$inbox/siirto${batch.id.toString}.xml")
      }
    case None => log.warning("sending files to YTL called without config")
  }



  def poll(batches: Seq[Batch[KokelasRequest]]): Unit = config match {
    case Some(YTLConfig(host:String, username: String, password: String, inbox: String, outbox: String, _)) =>
      SSH.ftp(host = host, username =username, password = SSHPassword(Some(password))){
        (sftp) =>
          for (
            batch <- batches
          ) for (
            result <- sftp.get(s"$outbox/outsiirto${batch.id.toString}.xml")
          ) {
            val response = XML.loadString(result)
            handleResponse(Some(batch), response)
            sent = sent.filterNot(_.id == batch.id)
          }
      }
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
      case Failure(t) if requested.isDefined => log.error(s"failure in fetching results for ${requested.get.id}", t)
      case Failure(t) => log.error("failure fetching results from YTL", t)
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

case class Batch[A](id: UUID = UUID.randomUUID(), items: Seq[A] = Seq[A]()) {

  def +:[B >: A](elem: B): Batch[B] = Batch(this.id, elem +: this.items)
}

case class KokelasRequest(oid: String, hetu: String)

case class YtlResult(batch: UUID, data: Elem)

case class Kokelas(oid: String,
                   yo: Option[Suoritus],
                   lukio: Option[Suoritus],
                   yoTodistus: Seq[Koe])

object Send

object Poll

object CheckSend

object CheckPoll


object YTLXml {


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
      Kokelas(oid, yo , extractLukio(oid, kokelas), yo.map((tutkinto) => extractTodistus(tutkinto, kokelas)).getOrElse(Seq()) )
    }
  }


  val YTL: String = "1.2.246.562.10.43628088406"

  val yotutkinto = "1.2.246.562.5.2013061010184237348007"


  object YoTutkinto {

    def apply(suorittaja:String, valmistuminen: LocalDate, kieli:String) = {
      Suoritus(
        komo = yotutkinto,
        myontaja = YTL,
        tila = "VALMIS",
        valmistuminen = valmistuminen,
        henkiloOid = suorittaja,
        yksilollistaminen = yksilollistaminen.Ei,
        suoritusKieli = kieli,
        source = YTL)
    }
  }

  val kevat = "(\\d{4})K".r
  val syksy = "(\\d{4})S".r
  val suoritettu = "suor".r

  def parseKausi(kausi: String) = kausi match {
    case kevat(vuosi) => Some(new MonthDay(6, 1).toLocalDate(vuosi.toInt))
    case syksy(vuosi) => Some(new MonthDay(12, 21).toLocalDate(vuosi.toInt))
    case _ => None
  }


  def extractYo(oid: String, kokelas: Node): Option[Suoritus] =
    for (
      valmistuminen <- parseValmistuminen(kokelas)
    ) yield {
      val kieli = (kokelas \ "TUTKINTOKIELI").text
      YoTutkinto(suorittaja = oid, valmistuminen = valmistuminen, kieli = kieli)
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

  def extractTodistus(yo: Suoritus, kokelas: Node): Seq[Koe] = {
    (kokelas \\ "KOE").map{
      (koe: Node) =>
       val arvio = ArvioYo((koe \ "ARVOSANA").text, Some((koe \ "YHTEISPISTEMAARA").text.toInt))
       val valinnaisuus = (koe \ "AINEYHDISTELMAROOLI").text.toInt >= 60
        Koe(arvio, (koe \ "KOETUNNUS").text, valinnainen = valinnaisuus, parseKausi((koe \ "TUTKINTOKERTA").text).get)
    }

  }




}

case class Koe(arvio: ArvioYo, aine: String, valinnainen: Boolean, myonnetty: LocalDate) {

  def toArvosana(suoritus: Suoritus with Identified[UUID]) = {
    Arvosana(suoritus.id, arvio, aine: String, None, valinnainen: Boolean, Some(myonnetty), YTLXml.YTL)
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

case class YTLConfig(host:String, username: String, password: String, inbox: String, outbox: String, sendTimes: Seq[LocalTime])