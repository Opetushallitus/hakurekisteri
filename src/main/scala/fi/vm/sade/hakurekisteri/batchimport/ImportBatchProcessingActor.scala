package fi.vm.sade.hakurekisteri.batchimport

import java.util.UUID

import akka.actor.Status.Failure
import akka.actor._
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.henkilo._
import fi.vm.sade.hakurekisteri.integration.organisaatio.{OppilaitosResponse, Oppilaitos, Organisaatio}
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.{yksilollistaminen, VirallinenSuoritus}
import org.joda.time.{DateTime, LocalDate}

import scala.compat.Platform
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.xml.Node

object ProcessReadyBatches

case class ProcessedBatch(batch: ImportBatch with Identified[UUID])
case class FailedBatch(batch: ImportBatch with Identified[UUID], t: Throwable)

class ImportBatchProcessingActor(importBatchActor: ActorRef, henkiloActor: ActorRef, suoritusrekisteri: ActorRef, opiskelijarekisteri: ActorRef, organisaatioActor: ActorRef)(implicit val system: ActorSystem, val ec: ExecutionContext) extends Actor with ActorLogging {
  var processing = false
  var startTime = Platform.currentTime
  var batches: Seq[ImportBatch with Identified[UUID]] = Seq()

  val processStarter: Cancellable = system.scheduler.schedule(1.minutes, 30.seconds, self, ProcessReadyBatches)

  override def postStop(): Unit = {
    processStarter.cancel()
  }

  object Stop

  log.info("starting processing actor")

  override def receive: Receive = {
    case ProcessReadyBatches if !processing =>
      startTime = Platform.currentTime
      processing = true
      importBatchActor ! ImportBatchQuery(None, Some(BatchState.READY), Some("perustiedot"), Some(2))
      log.debug("queried for two ready perustiedot batches")

    case Stop =>
      log.warning("processing has been running for 30 minutes, stopping")
      context.children.foreach(a => context.stop(a))
      batches.foreach(b => importBatchActor ! b.copy(state = BatchState.FAILED))
      processing = false

    case b: Seq[ImportBatch with Identified[UUID]] =>
      batches = b
      b.foreach(batch => {
        context.actorOf(Props(new PerustiedotProcessingActor(batch, self, henkiloActor, suoritusrekisteri, opiskelijarekisteri, organisaatioActor)))
        log.info(s"started processing batch ${batch.id}")
      })

    case ProcessedBatch(b) =>
      importBatchActor ! b.copy(state = BatchState.DONE)
      batchProcessed(b.id)
      log.info(s"batch ${b.id} was processed successfully, processing took ${Platform.currentTime - startTime} ms")

    case FailedBatch(b, t) =>
      importBatchActor ! b.copy(state = BatchState.FAILED)
      batchProcessed(b.id)
      log.error(t, s"error processing batch ${b.id}, processing took ${Platform.currentTime - startTime} ms")
  }

  def batchProcessed(id: UUID) = {
    batches = batches.filterNot(_.id == id)
    if (batches.length == 0) processing = false
  }


  object ProcessingJammedException extends Exception

  class PerustiedotProcessingActor(b: ImportBatch with Identified[UUID], parent: ActorRef, henkiloActor: ActorRef, suoritusrekisteri: ActorRef, opiskelijarekisteri: ActorRef, organisaatioActor: ActorRef)
    extends Actor {

    var importHenkilot: Map[String, ImportHenkilo] = Map()
    var organisaatiot: Map[String, Option[Organisaatio]] = Map()

    var sentOpiskelijat: Seq[Opiskelija] = Seq()
    var sentSuoritukset: Seq[VirallinenSuoritus] = Seq()

    var savedOpiskelijat: Seq[Opiskelija] = Seq()
    var savedSuoritukset: Seq[VirallinenSuoritus] = Seq()

    def fetchAllOppilaitokset() = {
      importHenkilot.values.foreach((h: ImportHenkilo) => {
        organisaatiot = organisaatiot + (h.lahtokoulu -> None)
        h.suoritukset.foreach(s => organisaatiot = organisaatiot + (s.myontaja -> None))

        organisaatiot.foreach(t => organisaatioActor ! Oppilaitos(t._1))
      })
    }

    def saveHenkilo(h: ImportHenkilo, resolveOid: (String) => String) = h.tunniste match {
      case ImportOppijanumero(oppijanumero) =>
        henkiloActor ! CheckHenkilo(h.tunniste.tunniste)
        // FIXME henkilö oidilla tuleville henkilöille ei päivitetä organisaatiohenkilöä henkilöpalveluun

      case _ =>
        henkiloActor ! SaveHenkilo(h.toHenkilo(resolveOid), h.tunniste.tunniste)
    }

    def saveOpiskelija(henkiloOid: String, importHenkilo: ImportHenkilo) = {
      val opiskelija = Opiskelija(
        oppilaitosOid = organisaatiot(importHenkilo.lahtokoulu).get.oid,
        luokkataso = detectLuokkataso(importHenkilo.suoritukset),
        luokka = importHenkilo.luokka,
        henkiloOid = henkiloOid,
        alkuPaiva = new DateTime().minusYears(1).withDayOfMonth(1).withMonthOfYear(8).withMillisOfDay(0),
        source = b.source
      )
      sentOpiskelijat = sentOpiskelijat :+ opiskelija
      opiskelijarekisteri ! opiskelija
    }

    def saveSuoritukset(henkiloOid: String, importHenkilo: ImportHenkilo) = importHenkilo.suoritukset.foreach(s => {
      val suoritus = s.copy(
        myontaja = organisaatiot(s.myontaja).get.oid,
        henkilo = henkiloOid
      )
      sentSuoritukset = sentSuoritukset :+ suoritus
      suoritusrekisteri ! suoritus
    })

    def hasKomo(s: Seq[VirallinenSuoritus], oid: String): Boolean = s.exists(_.komo == oid)

    def detectLuokkataso(suoritukset: Seq[VirallinenSuoritus]): String = suoritukset match {
      case s if hasKomo(s, Config.lukioKomoOid)                   => "L"
      case s if hasKomo(s, Config.lukioonvalmistavaKomoOid)       => "ML"
      case s if hasKomo(s, Config.ammatillinenKomoOid)            => "AK"
      case s if hasKomo(s, Config.ammatilliseenvalmistavaKomoOid) => "M"
      case s if hasKomo(s, Config.ammattistarttiKomoOid)          => "A"
      case s if hasKomo(s, Config.valmentavaKomoOid)              => "V"
      case s if hasKomo(s, Config.lisaopetusKomoOid)              => "10"
      case s if hasKomo(s, Config.perusopetusKomoOid)             => "9"
      case _                                                      => ""
    }

    private object Start
    private object Stop
    system.scheduler.scheduleOnce(1.millisecond, self, Start)
    system.scheduler.scheduleOnce(10.minutes, self, Stop)

    def batchProcessed() = {
      parent ! ProcessedBatch(b)
      context.stop(self)
    }

    def batchFailed(t: Throwable) = {
      parent ! FailedBatch(b, t)
      context.stop(self)
    }

    def parseData(): Map[String, ImportHenkilo] = try {
      (b.data \ "henkilot" \ "henkilo").map(ImportHenkilo(_)(b.source)).groupBy(_.tunniste.tunniste).mapValues(_.head)
    } catch {
      case t: Throwable =>
        batchFailed(t)
        Map()
    }

    override def receive: Actor.Receive = {
      case Start =>
        importHenkilot = parseData()
        fetchAllOppilaitokset()

      case OppilaitosResponse(koodi, organisaatio) =>
        organisaatiot = organisaatiot + (koodi -> Some(organisaatio))
        if (!organisaatiot.values.exists(_.isEmpty))
          importHenkilot.values.foreach(h => {
            saveHenkilo(h, (lahtokoulu) => organisaatiot(lahtokoulu).map(_.oid).get)
          })

      case SavedHenkilo(henkiloOid, tunniste) =>
        val importHenkilo = importHenkilot(tunniste)
        saveOpiskelija(henkiloOid, importHenkilo)
        saveSuoritukset(henkiloOid, importHenkilo)
        importHenkilot = importHenkilot.filterNot(_._1 == tunniste)

      case s: VirallinenSuoritus =>
        savedSuoritukset = savedSuoritukset :+ s
        if (sentSuoritukset.size == savedSuoritukset.size && sentOpiskelijat.size == savedOpiskelijat.size) {
          batchProcessed()
        }

      case o: Opiskelija =>
        savedOpiskelijat = savedOpiskelijat :+ o
        if (sentSuoritukset.size == savedSuoritukset.size && sentOpiskelijat.size == savedOpiskelijat.size) {
          batchProcessed()
        }

      case Failure(t: Throwable) =>
        batchFailed(t)

      case Stop =>
        if (organisaatiot.values.exists(_.isEmpty))
          log.error(s"could not resolve all organisaatios in batch ${b.id}")
        if (sentOpiskelijat.size != savedOpiskelijat.size)
          log.error(s"all opiskelijat were not saved in batch ${b.id}")
        if (sentSuoritukset.size != savedSuoritukset.size)
          log.error(s"all suoritukset were not saved in batch ${b.id}")
        log.warning(s"stopped processing of batch ${b.id}")
        batchFailed(ProcessingJammedException)
    }
  }
}


import org.scalatra.util.RicherString._

trait ImportTunniste {
  val tunniste: String
}
case class ImportHetu(hetu: String) extends ImportTunniste {
  override def toString: String = s"hetu: $hetu"
  override val tunniste = hetu
}
case class ImportOppijanumero(oppijanumero: String) extends ImportTunniste {
  override def toString: String = s"oppijanumero: $oppijanumero"
  override val tunniste = oppijanumero
}
case class ImportHenkilonTunniste(henkilonTunniste: String, syntymaAika: String, sukupuoli: String) extends ImportTunniste {
  override def toString: String = s"henkilonTunniste: $henkilonTunniste, syntymaAika: $syntymaAika, sukupuoli $sukupuoli"
  override val tunniste = henkilonTunniste
}

case class ImportHenkilo(tunniste: ImportTunniste, lahtokoulu: String, luokka: String, sukunimi: String, etunimet: String,
                         kutsumanimi: String, kotikunta: String, aidinkieli: String, kansalaisuus: Option[String],
                         lahiosoite: Option[String], postinumero: Option[String], maa: Option[String], matkapuhelin: Option[String],
                         muuPuhelin: Option[String], suoritukset: Seq[VirallinenSuoritus], lahde: String) {
  import HetuUtil._
  val mies = "1"
  val nainen = "2"

  def toHenkilo(resolveOid: (String) => String): CreateHenkilo = {
    val (syntymaaika: Option[String], sukupuoli: Option[String]) = tunniste match {
      case ImportHenkilonTunniste(_, syntymaAika, sukup) => (Some(syntymaAika), Some(sukup))
      case ImportHetu(Hetu(hetu)) =>
        if (hetu.charAt(9).toInt % 2 == 0)
          (toSyntymaAika(hetu), Some(nainen))
        else
          (toSyntymaAika(hetu), Some(mies))
      case _ => (None, None)
    }
    CreateHenkilo(
      etunimet = etunimet,
      kutsumanimi = kutsumanimi,
      sukunimi = sukunimi,
      hetu = tunniste match {
        case ImportHetu(h) => Some(h)
        case _ => None
      },
      syntymaaika = syntymaaika,
      sukupuoli = sukupuoli,
      asiointiKieli = Kieli(aidinkieli.toLowerCase),
      henkiloTyyppi = "OPPIJA",
      kasittelijaOid = lahde,
      organisaatioHenkilo = Seq(OrganisaatioHenkilo(resolveOid(lahtokoulu)))
    )
  }
}

object ImportHenkilo {
  def getField(name: String)(h: Node): String = (h \ name).head.text
  def getOptionField(name: String)(h: Node): Option[String] = (h \ name).headOption.flatMap(_.text.blankOption)
  def suoritus(name: String, komoOid: String, oppijanumero: Option[String], yksilollistetty: Boolean)(h: Node)(lahde: String): Option[VirallinenSuoritus] = (h \ name).headOption.map(s => {
    val valmistuminen = getField("valmistuminen")(s)
    val myontaja = getField("myontaja")(s)
    val suorituskieli = getField("suorituskieli")(s)
    val tila = getField("tila")(s)
    val yks = yksilollistetty match {
      case true => yksilollistaminen.withName(getField("yksilollistaminen")(s).toLowerCase.capitalize)
      case false => yksilollistaminen.Ei
    }
    VirallinenSuoritus(
      komo = komoOid,
      myontaja = myontaja,
      tila = tila,
      valmistuminen = new LocalDate(valmistuminen),
      henkilo = oppijanumero.getOrElse(""),
      yksilollistaminen = yks,
      suoritusKieli = suorituskieli,
      lahde = lahde
    )
  })

  def apply(h: Node)(lahde: String): ImportHenkilo = {
    val hetu = getOptionField("hetu")(h)
    val oppijanumero = getOptionField("oppijanumero")(h)
    val henkiloTunniste = getOptionField("henkiloTunniste")(h)
    val syntymaAika = getOptionField("syntymaAika")(h)
    val sukupuoli = getOptionField("sukupuoli")(h)

    val tunniste = (hetu, oppijanumero, henkiloTunniste, syntymaAika, sukupuoli) match {
      case (Some(henkilotunnus), _, _, _, _) => ImportHetu(henkilotunnus)
      case (_, Some(o), _, _, _) => ImportOppijanumero(o)
      case (_, _, Some(t), Some(sa), Some(sp)) => ImportHenkilonTunniste(t, sa, sp)
      case t =>
        throw new IllegalArgumentException(s"henkilo could not be identified: hetu, oppijanumero or henkiloTunniste+syntymaAika+sukupuoli missing $t")
    }

    val suoritukset = Seq(
      suoritus("perusopetus", Config.perusopetusKomoOid, oppijanumero, yksilollistetty = true)(h)(lahde),
      suoritus("perusopetuksenlisaopetus", Config.lisaopetusKomoOid, oppijanumero, yksilollistetty = true)(h)(lahde),
      suoritus("ammattistartti", Config.ammattistarttiKomoOid, oppijanumero, yksilollistetty = false)(h)(lahde),
      suoritus("valmentava", Config.valmentavaKomoOid, oppijanumero, yksilollistetty = false)(h)(lahde),
      suoritus("maahanmuuttajienlukioonvalmistava", Config.lukioonvalmistavaKomoOid, oppijanumero, yksilollistetty = false)(h)(lahde),
      suoritus("maahanmuuttajienammvalmistava", Config.ammatilliseenvalmistavaKomoOid, oppijanumero, yksilollistetty = false)(h)(lahde),
      suoritus("ulkomainen", Config.ulkomainenkorvaavaKomoOid, oppijanumero, yksilollistetty = false)(h)(lahde),
      suoritus("lukio", Config.lukioKomoOid, oppijanumero, yksilollistetty = false)(h)(lahde),
      suoritus("ammatillinen", Config.ammatillinenKomoOid, oppijanumero, yksilollistetty = false)(h)(lahde)
    ).flatten

    ImportHenkilo(
      tunniste = tunniste,
      lahtokoulu = getField("lahtokoulu")(h),
      luokka = getField("luokka")(h),
      sukunimi = getField("sukunimi")(h),
      etunimet = getField("etunimet")(h),
      kutsumanimi = getField("kutsumanimi")(h),
      kotikunta = getField("kotikunta")(h),
      aidinkieli = getField("aidinkieli")(h),
      kansalaisuus = getOptionField("kansalaisuus")(h),
      lahiosoite = getOptionField("lahiosoite")(h),
      postinumero = getOptionField("postinumero")(h),
      maa = getOptionField("maa")(h),
      matkapuhelin = getOptionField("matkapuhelin")(h),
      muuPuhelin = getOptionField("muuPuhelin")(h),
      suoritukset = suoritukset,
      lahde = lahde
    )
  }
}
