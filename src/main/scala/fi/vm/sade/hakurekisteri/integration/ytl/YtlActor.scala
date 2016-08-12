package fi.vm.sade.hakurekisteri.integration.ytl

import java.io.{File, FileOutputStream, IOException}
import java.util.UUID
import java.util.concurrent.{Executors, TimeUnit}

import akka.actor._
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import com.jcraft.jsch.{ChannelSftp, SftpException}
import fi.vm.sade.hakurekisteri.Oids
import fi.vm.sade.hakurekisteri.arvosana.{ArvioOsakoe, ArvioYo, Arvosana, _}
import fi.vm.sade.hakurekisteri.integration.hakemus.{FullHakemus, HakemusService}
import fi.vm.sade.hakurekisteri.integration.henkilo.{Henkilo, HetuQuery}
import fi.vm.sade.hakurekisteri.integration.ytl.YTLXml.Aine
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusQuery, VirallinenSuoritus, yksilollistaminen}
import fr.janalyse.ssh.{SSH, SSHFtp, SSHOptions, SSHPassword}
import org.joda.time._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.{Failure, Success}
import scala.xml.pull.{EvElemEnd, EvElemStart, EvText, _}
import scala.xml.{NamespaceBinding, _}


case class YtlReport(waitingforAnswers: Seq[Batch[KokelasRequest]], nextSend: Option[DateTime])

object Report

class YtlActor(henkiloActor: ActorRef, suoritusRekisteri: ActorRef, arvosanaRekisteri: ActorRef, hakemusService: HakemusService, config: Option[YTLConfig]) extends Actor with ActorLogging {
  implicit val ec = context.dispatcher

  var haut = Set[String]()
  var sent = Seq[Batch[KokelasRequest]]()

  def startSaveTicker() {
    saveTicker = Some(context.system.scheduler.schedule(1.seconds, 30.seconds, self, IsSaving))
  }

  val sendTicker = context.system.scheduler.schedule(5.minutes, 1.minutes, self, CheckSend)
  val pollTicker = context.system.scheduler.schedule(5.minutes, 5.minutes, self, Poll)
  var saveTicker: Option[Cancellable] = None

  var nextSend: Option[DateTime] = nextSendTime

  val sftpSendContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

  val sftpPollContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

  def nextSendTime: Option[DateTime] = {
    val times = config.map(_.sendTimes).filter(_.nonEmpty)
    Timer.countNextSend(times)
  }

  if (config.isEmpty) log.warning("Starting ytlActor without config")

  var kokelaat = Map[String, Kokelas]()
  var suoritusKokelaat = Map[UUID, (Suoritus with Identified[UUID], Kokelas)]()

  def getNewBatch(haut: Set[String]) = {
    implicit val to: Timeout = 300.seconds
    val haetaan: Set[Future[Seq[FullHakemus]]] = for (
      haku <- haut
    ) yield hakemusService.hakemuksetForHaku(haku, None).
        recoverWith{case t: Throwable => Future.failed(new HakuException(s"failed to find applications for applicationID $haku", haku, t))}

    Future.sequence(haetaan).map(
      _.toSeq.flatten.
        map((hakemus) => (hakemus.personOid, hakemus.hetu)).
        collect{case (Some(oid), Some(hetu)) => KokelasRequest(oid, hetu)}).
      map((rs) => Batch(items = rs.toSet))
  }


  @throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    sendTicker.cancel()
    pollTicker.cancel()
    saveTicker.foreach(_.cancel())
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

    case SentBatch(batch) => sent = batch +: sent

    case akka.actor.Status.Failure(h: HakuException) =>
      log.info(s"retrying after haku exception: ${h.message}: ${h.getCause.getMessage}")
      context.system.scheduler.scheduleOnce(5.seconds, self, Send)

    case akka.actor.Status.Failure(h: FtpException) =>
      log.info(s"retrying after FTP exception: ${h.getCause.getMessage}")
      context.system.scheduler.scheduleOnce(5.seconds, self, Send)

    case akka.actor.Status.Failure(t: Throwable) =>
      log.error(t, s"got failure from ${sender()}")

    case Poll if config.isDefined  => poll(sent)

    case YtlResult(id, file) =>
      val requested = sent.find(_.id == id)
      sent = sent.filterNot(_.id == id)
      handleResponse(requested, Source.fromFile(file, "ISO-8859-1"))
      startSaveTicker()

    case k: Kokelas =>
      log.debug(s"sending ytl data for ${k.oid} yo: ${k.yo} lukio: ${k.lukio}")
      context.actorOf(Props(new YoSuoritusUpdateActor(k.yo, suoritusRekisteri)))
      kokelaat = kokelaat + (k.oid -> k)
      k.lukio foreach (suoritusRekisteri ! _)

    case vs: VirallinenSuoritus with Identified[_] if vs.id.isInstanceOf[UUID] && vs.komo == YTLXml.yotutkinto =>
      val s = vs.asInstanceOf[VirallinenSuoritus with Identified[UUID]]
      for (
        kokelas <- kokelaat.get(s.henkiloOid)
      ) {
        context.actorSelection(s.id.toString) ! Identify(s.id)
        kokelaat = kokelaat - kokelas.oid
        suoritusKokelaat = suoritusKokelaat + (s.id -> (s, kokelas))
      }

    case ActorIdentity(id: UUID, Some(ref)) =>
      for (
        (suoritus, kokelas) <- suoritusKokelaat.get(id)
      ) {
        ref ! kokelas
        suoritusKokelaat = suoritusKokelaat - id
      }

    case ActorIdentity(id: UUID, None) =>
      try {
        for (
          (suoritus, kokelas) <- suoritusKokelaat.get(id)
        ) {
          context.actorOf(Props(new ArvosanaUpdateActor(suoritus, kokelas.yoTodistus ++ kokelas.osakokeet, arvosanaRekisteri)), id.toString)
          suoritusKokelaat = suoritusKokelaat - id
        }
      } catch {
        case t: Throwable =>
          context.actorSelection(id.toString) ! Identify(id)
          log.warning(s"problem creating arvosana update for ${id.toString} retrying search", t)
      }

    case IsSaving =>
      if (context.children.isEmpty) {
        saveTicker.foreach(_.cancel())
        saveTicker = None
        log.info("all arvosanat saved")
      }
  }

  def batchMessage(batch: Batch[KokelasRequest]) =
    <Haku id={batch.id.toString}>
      {for (kokelas <- batch.items) yield <Hetu>{kokelas.hetu}</Hetu>}
    </Haku>

  def uploadFile(batch: Batch[KokelasRequest], localStore: String): (String, String) = {
    val xml = batchMessage(batch)
    val fileName = s"siirto${batch.id.toString}.xml"
    val filePath = s"$localStore/$fileName"
    XML.save(filePath, xml,"ISO-8859-1", xmlDecl = true, doctype = null)
    (fileName, filePath)
  }

  def send(batch: Batch[KokelasRequest]) = config match {
    case Some(YTLConfig(host, username, password, inbox, outbox, _, localStore)) =>
      Future {
        val (filename, localCopy) = uploadFile(batch, localStore)
        log.info(s"sending file $filename to YTL")
        SSH.ftp(host = host, username =username, password = SSHPassword(Some(password))) {
          (sftp: SSHFtp) =>
            sftp.send(localCopy, s"$inbox/$filename")
            log.info(s"file $filename sent")
        }
        batch
      }(sftpSendContext).recoverWith {
        case t: Throwable => Future.failed(FtpException(t))
      }

    case None => log.warning("sending files to YTL called without config")
      Future.failed(new NoSuchElementException("No Config"))
  }

  def poll(batches: Seq[Batch[KokelasRequest]]): Unit = config match {
    case Some(YTLConfig(host, username, password, inbox, outbox, _, localStore)) =>
      Future {
        import scala.language.reflectiveCalls
        def using[T <: { def close() }, R](resource: T)(block: T => R) = {
          try block(resource)
          finally resource.close()
        }

        class YtlClient(implicit ssh: SSH) {
          private val channel: ChannelSftp = {
            val ch = ssh.jschsession.openChannel("sftp").asInstanceOf[ChannelSftp]
            ch.connect(ssh.options.connectTimeout.toInt)
            ch
          }

          def pollResponse(batch: Batch[KokelasRequest]): Option[String] =  {
            val filename = s"outsiirto${batch.id.toString}.xml"
            val path = s"$outbox/$filename"
            val outputStream = new FileOutputStream(new File(s"$localStore/$filename"))

            def deleteOutsiirtoFile() = try {
              if (!new File(s"$localStore/$filename").delete()) log.warning(s"file $localStore/$filename was not deleted")
            } catch {
              case t: Throwable =>
                log.warning(s"error deleting file $localStore/$filename: $t")
            }

            val outsiirto = try {
              channel.get(path, outputStream)
              Some(s"$localStore/$filename")
            } catch {
              case e: SftpException if e.id == 2 =>
                None
            } finally {
              outputStream.close()
            }

            if (outsiirto.isEmpty) deleteOutsiirtoFile()

            outsiirto
          }

          def close() = {
            channel.quit()
            channel.disconnect()
          }
        }

        val options = SSHOptions(host = host, username = username, password = SSHPassword(Some(password)))
        using(new SSH(options)) { implicit ssh =>
          using(new YtlClient) { ytl =>
            for (
              batch <- batches
            ) {
              val filename = s"outsiirto${batch.id.toString}.xml"
              val path = s"$outbox/$filename"
              log.debug(s"polling for $path")
              for (
                result <- ytl.pollResponse(batch)
              ) self ! YtlResult(batch.id, result)
            }
          }
        }
      }(sftpPollContext)
    case None => log.warning("polling of files from YTL called without config")
  }

  def handleResponse(requested: Option[Batch[KokelasRequest]], source: Source) {
    Future {
      log.info("started processing YTL response")


      val failed = Future.failed(new NoSuchElementException("can't find oid for hetu in requested data"))

      def map2Finder(hetuMap: Map[String, Future[String]])(hetu: String): Future[String] = {
        hetuMap.getOrElse(hetu, failed)
      }

    val hetuMap = requested.map(_.items.map { case KokelasRequest(oid, kokelasHetu) => kokelasHetu -> Future.successful(oid)}.toMap)

    val finder = hetuMap.map(map2Finder).getOrElse(resolveOidFromHenkiloPalvelu _)

    import YTLXml._

    val kokelaat = findKokelaat(source, finder)

    for (
      kokelasFut <- kokelaat
    ) kokelasFut.onComplete{
      case Failure(f) ⇒ self ! Status.Failure(f)
      case Success(Some(kokelas))  =>
        log.debug(s"sending kokelas ${kokelas.oid} for saving")
        self ! kokelas
      case _ => log.info(s"ytl result with no exams found, discarding it")
    }

    Future.sequence(kokelaat).onComplete {
      case Success(parsed) if requested.isDefined =>
        val batch = requested.get
        val found = parsed.collect{case Some(k) => k}.map(_.oid).toSet
        val missing = batch.items.map(_.oid).toSet -- found
        for (problem <- missing) log.warning(s"Missing result from YTL for oid $problem in batch ${batch.id}")
        log.info(s"process returned ${found.size} results of ${batch.items.size} requested, started saving")

      case Failure(t) if requested.isDefined => log.error(t, s"failure in fetching results for ${requested.get.id}")

      case Failure(t) => log.error(t, "failure fetching results from YTL")

      case _ =>  log.warning("no request in memory for a result from YTL")
    }}
  }

  def resolveOidFromHenkiloPalvelu(hetu: String): Future[String] =
  {
    implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)
    (henkiloActor ? HetuQuery(hetu)).mapTo[Henkilo].map(_.oidHenkilo).flatMap(Future.successful)
  }
}

case class Batch[A](id: UUID = UUID.randomUUID(), items: Set[A] = Set[A]()) {
  def +(elem: A): Batch[A] = Batch(this.id, this.items + elem)
}

case class KokelasRequest(oid: String, hetu: String)

case class YtlResult(batch: UUID, file: String)

case class Kokelas(oid: String,
                   yo: VirallinenSuoritus,
                   lukio: Option[Suoritus],
                   yoTodistus: Seq[Koe],
                   osakokeet: Seq[Koe])

object IsSaving

object Send

object Poll

object CheckSend

object CheckPoll

object Timer {
  implicit val dtOrder: Ordering[LocalTime] = new Ordering[LocalTime] {
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
    val aineet = Map(
      "SA" -> Aine("A", "SA"),
      "EA" -> Aine("A", "EN"),
      "EB" -> Aine("B", "EN"),
      "HA" -> Aine("A", "UN"),
      "FB" -> Aine("B", "RA"),
      "E2" -> Aine("B", "EN"),
      "M" -> Aine("PITKA", "MA"),
      "SC" -> Aine("C", "SA"),
      "VA" -> Aine("A", "VE"),
      "F2" -> Aine("B", "RA"),
      "GB" -> Aine("B", "PG"),
      "PS" -> Aine("AINEREAALI", "PS"),
      "I" -> Aine("AI", "IS"),
      "HI" -> Aine("AINEREAALI", "HI"),
      "V2" -> Aine("B", "VE"),
      "RY" -> Aine("REAALI", "ET"),
      "TA" -> Aine("A", "IT"),
      "CB" -> Aine("B", "FI"),
      "CC" -> Aine("C", "FI"),
      "S9" -> Aine("SAKSALKOUL", "SA"),
      "G2" -> Aine("B", "PG"),
      "V1" -> Aine("A", "VE"),
      "HB" -> Aine("B", "UN"),
      "TB" -> Aine("B", "IT"),
      "O" -> Aine("AI", "RU"),
      "A" -> Aine("AI", "FI"),
      "P1" -> Aine("A", "ES"),
      "GC" -> Aine("C", "PG"),
      "S2" -> Aine("B", "SA"),
      "PC" -> Aine("C", "ES"),
      "FY" -> Aine("AINEREAALI", "FY"),
      "EC" -> Aine("C", "EN"),
      "L1" -> Aine("D", "LA"),
      "H1" -> Aine("A", "UN"),
      "O5" -> Aine("VI2", "RU"),
      "FA" -> Aine("A", "RA"),
      "CA" -> Aine("A", "FI"),
      "F1" -> Aine("A", "RA"),
      "J" -> Aine("KYPSYYS", "EN"),
      "A5" -> Aine("VI2", "FI"),
      "Z" -> Aine("AI", "ZA"),
      "IC" -> Aine("C", "IS"),
      "KE" -> Aine("AINEREAALI", "KE"),
      "T1" -> Aine("A", "IT"),
      "RO" -> Aine("REAALI", "UO"),
      "YH" -> Aine("AINEREAALI", "YH"),
      "BA" -> Aine("A", "RU"),
      "H2" -> Aine("B", "UN"),
      "BI" -> Aine("AINEREAALI", "BI"),
      "VC" -> Aine("C", "VE"),
      "FF" -> Aine("AINEREAALI", "FF"),
      "BB" -> Aine("B", "RU"),
      "E1" -> Aine("A", "EN"),
      "T2" -> Aine("B", "IT"),
      "DC" -> Aine("C", "ZA"),
      "GE" -> Aine("AINEREAALI", "GE"),
      "P2" -> Aine("B", "ES"),
      "TC" -> Aine("C", "IT"),
      "G1" -> Aine("A", "PG"),
      "UO" -> Aine("AINEREAALI", "UO"),
      "RR" -> Aine("REAALI", "UE"),
      "VB" -> Aine("B", "VE"),
      "KC" -> Aine("C", "KR"),
      "ET" -> Aine("AINEREAALI", "ET"),
      "PB" -> Aine("B", "ES"),
      "SB" -> Aine("B", "SA"),
      "S1" -> Aine("A", "SA"),
      "QC" -> Aine("C", "QC"),
      "N" -> Aine("LYHYT", "MA"),
      "L7" -> Aine("C", "LA"),
      "PA" -> Aine("A", "ES"),
      "FC" -> Aine("C", "RA"),
      "TE" -> Aine("AINEREAALI", "TE"),
      "GA" -> Aine("A", "PG"),
      "UE" -> Aine("AINEREAALI", "UE"),
      "W" -> Aine("AI", "QS")
    )

    def apply(koetunnus:String, aineyhdistelmärooli: Option[String] = None):Aine =
      if (aineyhdistelmärooli == Some("22"))
        Aine("TOINENKIELI", aineet(koetunnus).lisatiedot)
      else
        aineet(koetunnus)
  }

  def findKokelaat(data: Elem): Seq[Node] = {
    data \\ "YLIOPPILAS"
  }

  def findKokelaat(source:Source, oidFinder: String => Future[String])(implicit ec: ExecutionContext): Seq[Future[Option[Kokelas]]] = {
    import Stream._
    val reader = new XMLEventReader(source)
    def youTutkinto(valmistuminen: Option[LocalDate], oid: String, kieli: Option[String]): VirallinenSuoritus = {
      val suoritettu = for (
        valm <- valmistuminen
      ) yield YoTutkinto(suorittaja = oid, valmistuminen = valm, kieli = kieli.get)

      val yo = suoritettu.getOrElse(
        YoTutkinto(suorittaja = oid,
          valmistuminen = parseKausi(nextKausi).get,
          kieli = kieli.get,
          valmis = false)
      )
      yo
    }
    def kokelaat(reader: XMLEventReader):Stream[Future[Option[Kokelas]]] = {

      def isKokelasElement(label:String) = "YLIOPPILAS".equalsIgnoreCase(label)

      def parseSingleElem(elemStart: EvElemStart, reader:XMLEventReader):Elem = {
        val pre = elemStart.pre
        val label = elemStart.label
        def loop(cur:Elem = Elem(pre, label, elemStart.attrs: MetaData, elemStart.scope: NamespaceBinding, minimizeEmpty = true)):Elem = reader.next() match {
          case e: EvElemStart =>
            loop(cur.copy(child = cur.child ++ Seq(parseSingleElem(e, reader))))
          case EvElemEnd(`pre`, `label`) =>
            cur
          case EvText(text) => loop(cur.copy(child = cur.child ++ Seq(Text(text))))
          case event =>
            loop(cur)
        }
        loop()
      }

      def readSingleText(label:String, reader:XMLEventReader): Option[String] = {
        val text = reader.collectFirst{
          case EvText(t) => Some(t)
          case EvElemEnd(_, `label`) => None
        }.get
        if (text.isDefined) reader.collectFirst{case EvElemEnd(_, `label`) => true}.get
        text
      }

      def parseOsakokeet(reader:XMLEventReader):Seq[(ArvioOsakoe, String)] = {
        def loop(currentarvio: Option[ArvioOsakoe], currenttunnus: Option[String],found: Seq[(ArvioOsakoe, String)]):Seq[(ArvioOsakoe, String)] =  reader.next() match {
          case EvElemEnd(_,"OSAKOE") => loop(None, None, found ++ Seq((currentarvio.get, currenttunnus.get)))
          case EvElemStart(_,"OSAKOEPISTEET", _,_) => loop(readSingleText("OSAKOEPISTEET", reader).map(ArvioOsakoe(_)), currenttunnus, found)
          case EvElemStart(_,"OSAKOETUNNUS", _, _) => loop(currentarvio, readSingleText("OSAKOETUNNUS", reader), found)
          case EvElemEnd(_,"OSAKOKEET") => found
          case _ => loop(currentarvio, currenttunnus, found)
        }
        loop(None, None, Seq())
      }

      def parseKoe(reader:XMLEventReader):(YoKoe, Seq[Osakoe]) = {
        def loop(arvosana:Option[String] = None,
                 pisteet: Option[Int] = None,
                 koetunnus: Option[String] = None,
                 aineryhdistelmarooli: Option[String] = None,
                 myonnetty: Option[LocalDate] = None,
                 osakokeet: Seq[(ArvioOsakoe, String)] = Seq()):(YoKoe, Seq[Osakoe]) = reader.next() match {
          case EvElemEnd(_,"KOE") =>
            val finalOsakokeet = osakokeet.map{
              case (arvio, osakoetunnus) => Osakoe(arvio, koetunnus.get, osakoetunnus, aineryhdistelmarooli.get, myonnetty.get)
            }
            (YoKoe(ArvioYo(arvosana.get, pisteet), koetunnus.get, aineryhdistelmarooli.get: String, myonnetty.get: LocalDate), finalOsakokeet)
          case EvElemStart(_,"ARVOSANA", _, _) => loop(readSingleText("ARVOSANA", reader), pisteet, koetunnus, aineryhdistelmarooli, myonnetty, osakokeet)
          case EvElemStart(_,"YHTEISPISTEMAARA",_,_) => loop(arvosana, readSingleText("YHTEISPISTEMAARA", reader).map(_.toInt), koetunnus, aineryhdistelmarooli, myonnetty, osakokeet)
          case EvElemStart(_,"KOETUNNUS",_,_) => loop(arvosana, pisteet, readSingleText("KOETUNNUS", reader), aineryhdistelmarooli, myonnetty, osakokeet)
          case EvElemStart(_,"AINEYHDISTELMAROOLI",_,_) => loop(arvosana, pisteet, koetunnus , readSingleText("AINEYHDISTELMAROOLI", reader), myonnetty, osakokeet)
          case EvElemStart(_,"TUTKINTOKERTA",_,_) =>
            loop(arvosana, pisteet, koetunnus , aineryhdistelmarooli, readSingleText("TUTKINTOKERTA",reader).flatMap(parseKausi), osakokeet)
          case e:EvElemStart if "OSAKOKEET".equals(e.label) =>
            loop(arvosana, pisteet, koetunnus , aineryhdistelmarooli, myonnetty, parseOsakokeet(reader))
          case _ => loop(arvosana, pisteet, koetunnus , aineryhdistelmarooli, myonnetty, osakokeet)


        }
        loop()
      }

      def parseKokelas(reader:XMLEventReader): Future[Option[Kokelas]] = {

        def loop(hetu: Option[String] = None,
                 yoaika: Option[String] = None,
                 tutkintoaika: Option[String] = None,
                 kieli: Option[String] = None,
                 lukio: Option[Suoritus] = None,

                 yoTodistus: Seq[Koe] = Seq(),
                 osakokeet: Seq[Koe] = Seq()):Future[Option[Kokelas]] = reader.next() match {
          case e:EvElemStart if "HENKILOTUNNUS".equalsIgnoreCase(e.label) =>
            loop(hetu = readSingleText("HENKILOTUNNUS", reader), yoaika, tutkintoaika, kieli, lukio, yoTodistus, osakokeet)

          case EvElemEnd(_, "YLIOPPILAS") if yoTodistus.isEmpty => Future.successful(None)

          case EvElemEnd(_, "YLIOPPILAS") =>
            hetu.map(oidFinder(_).map{(oid) =>
              val valmistuminen = if (yoaika.get.isEmpty) None
              else {
                yoaika.get match {
                  case suoritettu() =>
                    parseKausi(tutkintoaika.get)
                  case _ =>
                    parseKausi(yoaika.get)
                }
              }
              Some(Kokelas(oid, youTutkinto(valmistuminen, oid, kieli), lukio, yoTodistus, osakokeet))

            }).get
          case e:EvElemStart if "TUTKINTOKIELI".equalsIgnoreCase(e.label) =>
            loop(hetu, yoaika, tutkintoaika, kieli = readSingleText("TUTKINTOKIELI", reader), lukio, yoTodistus, osakokeet)

          case e:EvElemStart if "KOE".equalsIgnoreCase(e.label) =>

            val (koe, uudetOsakokeet) = parseKoe(reader)
            loop(hetu, yoaika, tutkintoaika, kieli, lukio, yoTodistus ++ Seq(koe), osakokeet ++ uudetOsakokeet)

          case e:EvElemStart if "YLIOPPILAAKSITULOAIKA".equalsIgnoreCase(e.label) =>
            loop(hetu, Some(readSingleText("YLIOPPILAAKSITULOAIKA", reader).getOrElse("")), tutkintoaika, kieli, lukio, yoTodistus, osakokeet)

          case e:EvElemStart if "TUTKINTOAIKA".equalsIgnoreCase(e.label) =>
            loop(hetu, yoaika, readSingleText("TUTKINTOAIKA", reader), kieli, lukio, yoTodistus, osakokeet)


          case e =>
            loop(hetu, yoaika, tutkintoaika, kieli, lukio, yoTodistus, osakokeet)
        }
        loop()
      }

      reader.collectFirst {
        case e:EvElemStart if isKokelasElement(e.label) =>
          e
      }.map((_) => parseKokelas(reader) #:: kokelaat(reader)).getOrElse(empty[Future[Option[Kokelas]]])
    }

    kokelaat(reader)
  }


  val YTL: String = Oids.ytlOrganisaatioOid
  val yotutkinto = Oids.yotutkintoKomoOid

  object YoTutkinto {
    def apply(suorittaja: String, valmistuminen: LocalDate, kieli: String, valmis: Boolean = true, vahvistettu: Boolean = true) = {
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
    ) yield YoTutkinto(suorittaja = oid, valmistuminen = valmistuminen, kieli = kieli)

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

  def extractLukio(oid: String, kokelas:Node): Option[Suoritus] = None

  import fi.vm.sade.hakurekisteri.tools.RicherString._

  def extractTodistus(yo: Suoritus, kokelas: Node): Seq[YoKoe] = {
    (kokelas \\ "KOE").map{
      extractKoe
    }
  }

  def extractKoe: (Node) => YoKoe = {
    (koe: Node) =>
      val arvio = ArvioYo((koe \ "ARVOSANA").text, (koe \ "YHTEISPISTEMAARA").text.blankOption.map(_.toInt))
      YoKoe(arvio, (koe \ "KOETUNNUS").text, (koe \ "AINEYHDISTELMAROOLI").text, parseKausi((koe \ "TUTKINTOKERTA").text).get)
  }

  def extractOsakoe(yo: Suoritus, kokelas: Node): Seq[Osakoe] = {
    (kokelas \\ "KOE").flatMap {
      extractOsakokeet
    }
  }

  def extractOsakokeet: (Node) => Seq[Osakoe] = {
    (koe: Node) =>
      val osakokeet = koe \\ "OSAKOE"
      osakokeet.map(osakoe => {
        val arvio = ArvioOsakoe((osakoe \ "OSAKOEPISTEET").text)
        Osakoe(arvio, (koe \ "KOETUNNUS").text, (osakoe \ "OSAKOETUNNUS").text, (koe \ "AINEYHDISTELMAROOLI").text, parseKausi((koe \ "TUTKINTOKERTA").text).get)
      })
  }
}

trait Koe {
  def toArvosana(suoritus: Suoritus with Identified[UUID]): Arvosana
}

case class Osakoe(arvio: ArvioOsakoe, koetunnus: String, osakoetunnus: String, aineyhdistelmarooli: String, myonnetty: LocalDate) extends Koe {
  val aine = Aine(koetunnus, Some(aineyhdistelmarooli))
  val valinnainen = aineyhdistelmarooli.toInt >= 60
  val lahdeArvot = Map("koetunnus" -> koetunnus, "aineyhdistelmarooli" -> aineyhdistelmarooli)

  def toArvosana(suoritus: Suoritus with Identified[UUID]) = {
    Arvosana(suoritus.id, arvio, aine.aine + "_" + osakoetunnus: String, Some(aine.lisatiedot), valinnainen: Boolean, Some(myonnetty), YTLXml.YTL, lahdeArvot)
  }
}

case class YoKoe(arvio: ArvioYo, koetunnus: String, aineyhdistelmarooli: String, myonnetty: LocalDate) extends Koe {
  val aine = Aine(koetunnus, Some(aineyhdistelmarooli))
  val valinnainen = aineyhdistelmarooli.toInt >= 60
  val lahdeArvot = Map("koetunnus" -> koetunnus, "aineyhdistelmarooli" -> aineyhdistelmarooli)

  def toArvosana(suoritus: Suoritus with Identified[UUID]):Arvosana = {
    Arvosana(suoritus.id, arvio, aine.aine: String, Some(aine.lisatiedot), valinnainen: Boolean, Some(myonnetty), YTLXml.YTL, lahdeArvot)
  }
}


class YoSuoritusUpdateActor(yoSuoritus: VirallinenSuoritus, suoritusRekisteri: ActorRef) extends Actor {
  private def ennenVuotta1990Valmistuneet(s: Seq[_]) = s.map {
    case v: VirallinenSuoritus with Identified[_] if v.id.isInstanceOf[UUID] =>
      v.asInstanceOf[VirallinenSuoritus with Identified[UUID]]
  }.filter(s => s.valmistuminen.isBefore(new LocalDate(1990, 1, 1)) && s.tila == "VALMIS" && s.vahvistettu)

  override def receive: Actor.Receive = {
    case s: Seq[_] =>
      fetch.foreach(_.cancel())
      if (s.isEmpty)
        suoritusRekisteri ! yoSuoritus
      else {
        val suoritukset = ennenVuotta1990Valmistuneet(s)
        if (suoritukset.nonEmpty) {
          context.parent ! suoritukset.head
          context.stop(self)
        } else {
          suoritusRekisteri ! yoSuoritus
        }
      }
    case v: VirallinenSuoritus with Identified[_] if v.id.isInstanceOf[UUID] =>
      context.parent ! v.asInstanceOf[VirallinenSuoritus with Identified[UUID]]
      context.stop(self)
  }

  var fetch: Option[Cancellable] = None

  override def preStart(): Unit = {
    implicit val ec = context.dispatcher
    fetch = Some(context.system.scheduler.schedule(1.millisecond, 130.seconds, suoritusRekisteri, SuoritusQuery(henkilo = Some(yoSuoritus.henkilo), komo = Some(Oids.yotutkintoKomoOid))))
  }
}


class ArvosanaUpdateActor(suoritus: Suoritus with Identified[UUID], var kokeet: Seq[Koe], arvosanaRekisteri: ActorRef) extends Actor {
  def isKorvaava(old: Arvosana) = (uusi: Arvosana) =>
    uusi.aine == old.aine && uusi.myonnetty == old.myonnetty && uusi.lisatieto == old.lisatieto && uusi.lahdeArvot == old.lahdeArvot

  override def receive: Actor.Receive = {
    case s: Seq[_] =>
      fetch.foreach(_.cancel())
      val uudet = kokeet.map(_.toArvosana(suoritus))
      s.map {
        case (as: Arvosana with Identified[_]) if as.id.isInstanceOf[UUID] =>
          val a = as.asInstanceOf[Arvosana with Identified[UUID]]
          val korvaava = uudet.find(isKorvaava(a))
          if (korvaava.isDefined) korvaava.get.identify(a.id)
          else a
      } foreach (arvosanaRekisteri ! _)
      uudet.filterNot((uusi) => s.exists { case old: Arvosana => isKorvaava(old)(uusi) }) foreach (arvosanaRekisteri ! _)
      context.stop(self)
    case Kokelas(_, _, _ , todistus, osakokeet) => kokeet = todistus ++ osakokeet
  }

  var fetch: Option[Cancellable] = None

  override def preStart(): Unit = {
    implicit val ec = context.dispatcher
    fetch = Some(context.system.scheduler.schedule(1.millisecond, 130.seconds, arvosanaRekisteri, ArvosanaQuery(Some(suoritus.id))))
  }
}

case class YTLConfig(host: String, username: String, password: String, inbox: String, outbox: String, sendTimes: Seq[LocalTime], localStore: String)

case class Handled(batches: Seq[UUID])

case class SentBatch(batch: Batch[KokelasRequest])

case class HakuList(haut: Set[String])

case class HakuException(message: String, haku: String, cause: Throwable) extends Exception(message, cause)

case class FtpException(cause: Throwable) extends IOException("problem with SFTP send", cause)

