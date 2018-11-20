package fi.vm.sade.hakurekisteri.batchimport

import java.util.UUID

import akka.actor.Status.Failure
import akka.actor._
import fi.vm.sade.hakurekisteri.{Config, Oids}
import fi.vm.sade.hakurekisteri.batchimport.BatchState.BatchState
import fi.vm.sade.hakurekisteri.integration.henkilo._
import fi.vm.sade.hakurekisteri.integration.organisaatio.{Oppilaitos, OppilaitosResponse, Organisaatio}
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.storage.repository.Updated
import fi.vm.sade.hakurekisteri.suoritus.{VirallinenSuoritus, yksilollistaminen}
import org.joda.time.{DateTime, LocalDate}

import scala.compat.Platform
import scala.concurrent.duration._
import scala.xml.Node
import fi.vm.sade.hakurekisteri.tools.RicherString

import scala.concurrent.ExecutionContext

object ProcessReadyBatches

class ImportBatchProcessingActor(importBatchOrgActor: ActorRef,
                                 importBatchActor: ActorRef,
                                 henkiloActor: ActorRef,
                                 suoritusrekisteri: ActorRef,
                                 opiskelijarekisteri: ActorRef,
                                 organisaatioActor: ActorRef,
                                 arvosanarekisteri: ActorRef,
                                 koodistoActor: ActorRef,
                                 config: Config) extends Actor with ActorLogging {

  implicit val ec: ExecutionContext = context.dispatcher
  private val processStarter: Cancellable = context.system.scheduler.schedule(config.importBatchProcessingInitialDelay, 15.seconds, self, ProcessReadyBatches)

  log.info(s"started $self")

  override def postStop(): Unit = {
    processStarter.cancel()
  }

  var fetching = false

  private def readyForProcessing: Boolean = context.children.size < 2 && !fetching

  override def receive: Receive = {
    case ProcessReadyBatches if readyForProcessing =>
      log.debug("checking for batches")
      fetching = true
      importBatchActor ! ImportBatchQuery(None, Some(BatchState.READY), None, if (config.mockMode) { None } else { Some(1) })

    case b: Seq[ImportBatch with Identified[UUID]] @unchecked =>
      b.take(1).foreach(batch => importBatchActor ! batch.copy(state = BatchState.PROCESSING).identify(batch.id))
      if (b.isEmpty) fetching = false

    case b: ImportBatch with Identified[UUID @unchecked] =>
      fetching = false
      log.debug("got import batch")
      b.batchType match {
        case "perustiedot" =>
          context.actorOf(Props(
            new PerustiedotProcessingActor(importBatchOrgActor, importBatchActor, henkiloActor, suoritusrekisteri, opiskelijarekisteri, organisaatioActor)(b)
          ))
        case "arvosanat" =>
          context.actorOf(Props(
            new ArvosanatProcessingActor(importBatchOrgActor, importBatchActor, henkiloActor, suoritusrekisteri, arvosanarekisteri, organisaatioActor, koodistoActor)(b)
          ))
        case t =>
          log.error(s"unknown batchType $t")
      }

    case Failure(t) =>
      log.error(t, s"got failure from ${sender()}")
      fetching = false
  }

}

object ProcessingJammedException extends Exception("processing jammed")

class ArvosanatProcessingActor(importBatchOrgActor: ActorRef,
                               importBatchActor: ActorRef,
                               henkiloActor: ActorRef,
                               suoritusrekisteri: ActorRef,
                               arvosanarekisteri: ActorRef,
                               organisaatioActor: ActorRef,
                               koodistoActor: ActorRef)(b: ImportBatch with Identified[UUID])
  extends Actor with ActorLogging {

  implicit val ec = context.dispatcher

  private val processor =
    new ArvosanatProcessing(importBatchOrgActor, organisaatioActor, henkiloActor, suoritusrekisteri, arvosanarekisteri, importBatchActor, koodistoActor)(context.system)

  private val startTime = Platform.currentTime
  log.info(s"started processing batch ${b.id}")

  private case object Start
  private case object Stop

  private val start = context.system.scheduler.scheduleOnce(1.millisecond, self, Start)

  override def postStop(): Unit = {
    start.cancel()
  }

  override def receive: Actor.Receive = {
    case Start =>
      processor.process(b).onComplete(t => {
        if (t.isSuccess) {
          log.info(s"batch ${b.id} was processed successfully, processing took ${Platform.currentTime - startTime} ms")
        } else {
          log.info(s"batch ${b.id} failed, processing took ${Platform.currentTime - startTime} ms")
          log.error(t.failed.get, s"batch ${b.id} failed")
        }
        self ! Stop
      })

    case Stop =>
      context.stop(self)

    case Failure(t) =>
      log.error(t, s"got failure from ${sender()}")
      context.stop(self)
  }
}

class PerustiedotProcessingActor(importBatchOrgActor: ActorRef,
                                 importBatchActor: ActorRef,
                                 henkiloActor: ActorRef,
                                 suoritusrekisteri: ActorRef,
                                 opiskelijarekisteri: ActorRef,
                                 organisaatioActor: ActorRef)(b: ImportBatch with Identified[UUID])
  extends Actor with ActorLogging {

  private val startTime = Platform.currentTime
  log.info(s"started processing batch ${b.id}")

  private var importHenkilot: Map[String, ImportHenkilo] = Map()
  private var organisaatiot: Map[String, Option[Organisaatio]] = Map()

  private var sentOpiskelijat: Seq[Opiskelija] = Seq()
  private var sentSuoritukset: Seq[VirallinenSuoritus] = Seq()

  private var savedOpiskelijat: Seq[Opiskelija] = Seq()
  private var savedSuoritukset: Seq[VirallinenSuoritus] = Seq()

  private var totalRows: Option[Int] = None
  private var failures: Map[String, Set[String]] = Map()

  private var savedReferences: Map[String, Map[String, String]] = Map()

  private def fetchAllOppilaitokset() = {
    importHenkilot.values.foreach((h: ImportHenkilo) => {
      organisaatiot = organisaatiot + (h.lahtokoulu -> None)
      h.suoritukset.foreach(s => organisaatiot = organisaatiot + (s.myontaja -> None))
    })
    organisaatiot.foreach(t => organisaatioActor ! Oppilaitos(t._1))
  }

  private def saveHenkilo(h: ImportHenkilo, resolveOid: (String) => String) =
    henkiloActor ! SaveHenkilo(h.toHenkilo(resolveOid), h.tunniste.tunniste)

  import ImportHenkilo.opiskelijaAlkuPaiva
  import ImportHenkilo.opiskelijaLoppuPaiva

  private def createOpiskelija(henkiloOid: String, importHenkilo: ImportHenkilo): Opiskelija = {
    Opiskelija(
      oppilaitosOid = organisaatiot(importHenkilo.lahtokoulu).get.oid,
      luokkataso = detectLuokkataso(importHenkilo.suoritukset),
      luokka = importHenkilo.luokka,
      henkiloOid = henkiloOid,
      alkuPaiva = opiskelijaAlkuPaiva,
      loppuPaiva = Some(opiskelijaLoppuPaiva),
      source = b.source
    )
  }

  private def saveOpiskelija(henkiloOid: String, importHenkilo: ImportHenkilo) = {
    val opiskelija = createOpiskelija(henkiloOid, importHenkilo)
    sentOpiskelijat = sentOpiskelijat :+ opiskelija
    opiskelijarekisteri ! opiskelija
  }

  private def saveSuoritukset(henkiloOid: String, importHenkilo: ImportHenkilo) = importHenkilo.suoritukset.foreach(s => {
    val suoritus = s.copy(
      myontaja = organisaatiot(s.myontaja).get.oid,
      henkilo = henkiloOid
    )
    sentSuoritukset = sentSuoritukset :+ suoritus
    suoritusrekisteri ! suoritus
  })

  private def hasKomo(s: Seq[VirallinenSuoritus], oid: String): Boolean = s.exists(_.komo == oid)

  //noinspection ScalaStyle
  private def detectLuokkataso(suoritukset: Seq[VirallinenSuoritus]): String = suoritukset match {
    case s if hasKomo(s, Oids.lukioKomoOid)                   => "L"
    case s if hasKomo(s, Oids.lukioonvalmistavaKomoOid)       => "ML"
    case s if hasKomo(s, Oids.ammatillinenKomoOid)            => "AK"
    case s if hasKomo(s, Oids.ammatilliseenvalmistavaKomoOid) => "M"
    case s if hasKomo(s, Oids.ammattistarttiKomoOid)          => "A"
    case s if hasKomo(s, Oids.valmentavaKomoOid)              => "V"
    case s if hasKomo(s, Oids.valmaKomoOid)                   => "VALMA"
    case s if hasKomo(s, Oids.telmaKomoOid)                   => "TELMA"
    case s if hasKomo(s, Oids.lisaopetusKomoOid)              => "10"
    case s if hasKomo(s, Oids.perusopetusKomoOid)             => "9"
    case _                                                    => ""
  }

  private case object Start
  private case object Stop
  implicit val ec = context.dispatcher

  private val start = context.system.scheduler.scheduleOnce(1.millisecond, self, Start)
  private val stop = context.system.scheduler.scheduleOnce(1.hour, self, Stop)

  override def postStop() {
    start.cancel()
    stop.cancel()
  }

  def batch(b: ImportBatch with Identified[UUID], state: BatchState, batchFailure: Option[(String, Set[String])] = None): ImportBatch with Identified[UUID] = {
    b.copy(
      status = b.status.copy(
        processedTime = Some(new DateTime()),
        successRows = Some(savedOpiskelijat.size),
        failureRows = Some(failures.size),
        totalRows = Some(totalRows.getOrElse(0)),
        messages = batchFailure.map(failures + _).getOrElse(failures),
        savedReferences = Some(savedReferences)
      ),
      state = state
    ).identify(b.id)
  }

  private def batchProcessed() {
    importBatchActor ! batch(b, BatchState.DONE)

    log.info(s"batch ${b.id} was processed successfully, processing took ${Platform.currentTime - startTime} ms")

    context.stop(self)
  }

  private def batchFailed(t: Throwable) {
    importBatchActor ! batch(b, BatchState.FAILED, Some("Virhe tiedoston käsittelyssä", Set(t.toString)))

    log.info(s"batch ${b.id} failed, processing took ${Platform.currentTime - startTime} ms")
    log.error(t, s"batch ${b.id} failed")

    context.stop(self)
  }

  private def parseData(): Map[String, ImportHenkilo] = try {
    val henkilot = (b.data \ "henkilot" \ "henkilo").map(ImportHenkilo(_)(b.source)).groupBy(_.tunniste.tunniste).mapValues(_.head)
    totalRows = Some(henkilot.size)
    henkilot
  } catch {
    case t: Throwable =>
      batchFailed(t)
      Map()
  }

  private def henkiloDone(tunniste: String) {
    importHenkilot = importHenkilot.filterNot(_._1 == tunniste)
  }

  private def addReference(henkiloOid: String, refName: String, refId: String) {
    val henkiloRefs: Map[String, String] = savedReferences.get(henkiloOid) match {
      case Some(refs) => refs + (refName -> refId)
      case None =>
        log.error(s"henkiloOid $henkiloOid not found in savedReferences")
        Map[String, String]("tunniste" -> henkiloOid, refName -> refId)
    }
    savedReferences = savedReferences + (henkiloOid -> henkiloRefs)
  }

  //noinspection ScalaStyle
  def suoritusType(s: VirallinenSuoritus): String = s.komo match {
    case Oids.perusopetusKomoOid => "perusopetus"
    case Oids.lisaopetusKomoOid => "perusopetuksenlisaopetus"
    case Oids.ammattistarttiKomoOid => "ammattistartti"
    case Oids.valmentavaKomoOid => "valmentava"
    case Oids.lukioonvalmistavaKomoOid => "maahanmuuttajienlukioonvalmistava"
    case Oids.ammatilliseenvalmistavaKomoOid => "maahanmuuttajienammvalmistava"
    case Oids.ulkomainenkorvaavaKomoOid => "ulkomainen"
    case Oids.lukioKomoOid => "lukio"
    case Oids.ammatillinenKomoOid => "ammatillinen"
    case Oids.ammatillinentutkintoKomoOid => "ammatillinentutkinto"
    case Oids.erikoisammattitutkintoKomoOid => "erikoisammattitutkinto"
    case Oids.valmaKomoOid => "valma"
    case Oids.telmaKomoOid => "telma"
    case oid => oid
  }

  //noinspection ScalaStyle
  override def receive: Actor.Receive = {
    case Start =>
      importHenkilot = parseData()
      if (importHenkilot.isEmpty)
        batchProcessed()
      fetchAllOppilaitokset()

    case OppilaitosResponse(koodi, organisaatio) if organisaatiot.values.exists(_.isEmpty) =>
      organisaatiot = organisaatiot + (koodi -> Some(organisaatio))
      if (!organisaatiot.values.exists(_.isEmpty)) {
        importBatchOrgActor ! ImportBatchOrgs(b.id, organisaatiot.flatMap(_._2).map(_.oid).toSet)
        importHenkilot.values.foreach(h => {
          saveHenkilo(h, (lahtokoulu) => organisaatiot(lahtokoulu).map(_.oid).get)
        })
      }

    case SavedHenkilo(henkiloOid, tunniste) if importHenkilot.contains(tunniste) =>
      val importHenkilo = importHenkilot(tunniste)
      saveOpiskelija(henkiloOid, importHenkilo)
      saveSuoritukset(henkiloOid, importHenkilo)
      savedReferences = savedReferences + (henkiloOid -> Map("tunniste" -> tunniste))
      henkiloDone(tunniste)

    case SavedHenkilo(henkiloOid, tunniste) if !importHenkilot.contains(tunniste) =>
      log.warning(s"received save confirmation from ${sender()}, but no match left in batch: sent tunniste $tunniste -> received henkiloOid $henkiloOid")

    case Failure(HenkiloSaveFailed(tunniste, t)) =>
      val errors = failures.getOrElse(tunniste, Set[String]()) + t.toString
      failures = failures + (tunniste -> errors)
      henkiloDone(tunniste)
      if (importHenkilot.isEmpty && sentSuoritukset.size == savedSuoritukset.size && sentOpiskelijat.size == savedOpiskelijat.size) {
        batchProcessed()
      }

    case s: VirallinenSuoritus with Identified[UUID @unchecked] =>
      savedSuoritukset = savedSuoritukset :+ s
      addReference(s.henkiloOid, suoritusType(s), s.id.toString)
      if (importHenkilot.isEmpty && sentSuoritukset.size == savedSuoritukset.size && sentOpiskelijat.size == savedOpiskelijat.size) {
        batchProcessed()
      }

    case o: Opiskelija with Identified[UUID @unchecked] =>
      savedOpiskelijat = savedOpiskelijat :+ o
      addReference(o.henkiloOid, "opiskelija", o.id.toString)
      if (importHenkilot.isEmpty && sentSuoritukset.size == savedSuoritukset.size && sentOpiskelijat.size == savedOpiskelijat.size) {
        batchProcessed()
      }

    case Failure(t) =>
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

import RicherString._
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

import ImportHenkilo.opiskelijaAlkuPaiva
import ImportHenkilo.opiskelijaLoppuPaiva
import HetuUtil._

case class ImportHenkilo(tunniste: ImportTunniste, lahtokoulu: String, luokka: String, sukunimi: String, etunimet: String,
                         kutsumanimi: String, kotikunta: String, aidinkieli: String, kansalaisuus: Option[String],
                         lahiosoite: Option[String], postinumero: Option[String], maa: Option[String], matkapuhelin: Option[String],
                         muuPuhelin: Option[String], suoritukset: Seq[VirallinenSuoritus], lahde: String) {
  val mies = "1"
  val nainen = "2"
  val sukupuoliIndex = 9

  private def koulut = (lahtokoulu +: suoritukset.map(_.myontaja)).toSet.toList.sorted.mkString(",")

  private def isEven(c: Char): Boolean = c.toInt % 2 == 0

  def toHenkilo(resolveOid: (String) => String): CreateHenkilo = {
    val (syntymaaika: Option[String], sukupuoli: Option[String]) = tunniste match {
      case ImportHenkilonTunniste(_, syntymaAika, sukup) => (Some(syntymaAika), Some(sukup))
      case ImportHetu(Hetu(hetu)) =>
        if (isEven(hetu.charAt(sukupuoliIndex))) {
          (toSyntymaAika(hetu), Some(nainen))
        } else {
          (toSyntymaAika(hetu), Some(mies))
        }
      case _ => (None, None)
    }

    val vuosi = new LocalDate() match {
      case v if v.getMonthOfYear > 6 => v.getYear + 1
      case v => v.getYear
    }

    CreateHenkilo(
      etunimet = etunimet,
      kutsumanimi = kutsumanimi,
      sukunimi = sukunimi,
      hetu = extractHetu,
      oidHenkilo = extractOppijanumero,
      externalIds = extractHenkilontunniste(vuosi),
      syntymaaika = syntymaaika,
      sukupuoli = sukupuoli,
      aidinkieli = Some(Kieli(aidinkieli.toLowerCase)),
      henkiloTyyppi = "OPPIJA"
    )
  }

  private def extractHenkilontunniste(vuosi: Int): Option[Seq[String]] = tunniste match {
    case ImportHenkilonTunniste(t, sa, _) => Some(Seq(s"${koulut}_${vuosi}_${t}_$sa"))
    case _ => None
  }

  private def extractOppijanumero: Option[String] = tunniste match {
    case ImportOppijanumero(oid) => Some(oid)
    case _ => None
  }

  private def extractHetu: Option[String] = tunniste match {
    case ImportHetu(h) => Some(h)
    case _ => None
  }
}

object ImportHenkilo {
  def getField(name: String)(h: Node): String = (h \ name).head.text
  def getOptionField(name: String)(h: Node): Option[String] = (h \ name).headOption.flatMap(_.text.blankOption)
  def opiskelijaAlkuPaiva: DateTime = {
    val currentMonth = new LocalDate().monthOfYear().get
    val alku = new DateTime().withDayOfMonth(1).withMonthOfYear(8).withMillisOfDay(0)
    if (currentMonth > 6) alku else alku.minusYears(1)
  }
  def opiskelijaLoppuPaiva: DateTime = {
    val currentMonth = new LocalDate().monthOfYear().get
    val loppu = new DateTime().withDayOfMonth(1).withMonthOfYear(6).withMillisOfDay(0)
    if (currentMonth > 6) loppu.plusYears(1) else loppu
  }
  def suoritus(name: String, komoOid: String, oppijanumero: Option[String], yksilollistetty: Boolean)
              (h: Node)
              (lahde: String): Option[VirallinenSuoritus] = (h \ name).headOption.map(s => {
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
      suoritus("perusopetus", Oids.perusopetusKomoOid, oppijanumero, yksilollistetty = true)(h)(lahde),
      suoritus("perusopetuksenlisaopetus", Oids.lisaopetusKomoOid, oppijanumero, yksilollistetty = true)(h)(lahde),
      suoritus("ammattistartti", Oids.ammattistarttiKomoOid, oppijanumero, yksilollistetty = false)(h)(lahde),
      suoritus("valmentava", Oids.valmentavaKomoOid, oppijanumero, yksilollistetty = false)(h)(lahde),
      suoritus("maahanmuuttajienlukioonvalmistava", Oids.lukioonvalmistavaKomoOid, oppijanumero, yksilollistetty = false)(h)(lahde),
      suoritus("maahanmuuttajienammvalmistava", Oids.ammatilliseenvalmistavaKomoOid, oppijanumero, yksilollistetty = false)(h)(lahde),
      suoritus("ulkomainen", Oids.ulkomainenkorvaavaKomoOid, oppijanumero, yksilollistetty = false)(h)(lahde),
      suoritus("lukio", Oids.lukioKomoOid, oppijanumero, yksilollistetty = false)(h)(lahde),
      suoritus("ammatillinen", Oids.ammatillinenKomoOid, oppijanumero, yksilollistetty = false)(h)(lahde),
      suoritus("ammatillinentutkinto", Oids.ammatillinentutkintoKomoOid, oppijanumero, yksilollistetty = false)(h)(lahde),
      suoritus("erikoisammattitutkinto", Oids.erikoisammattitutkintoKomoOid, oppijanumero, yksilollistetty = false)(h)(lahde),
      suoritus("valma", Oids.valmaKomoOid, oppijanumero, yksilollistetty = false)(h)(lahde),
      suoritus("telma", Oids.telmaKomoOid, oppijanumero, yksilollistetty = false)(h)(lahde)
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
