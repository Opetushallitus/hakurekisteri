package fi.vm.sade.hakurekisteri.batchimport

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.{Oids, Config}
import fi.vm.sade.hakurekisteri.arvosana.{Arvio410, Arvosana}
import fi.vm.sade.hakurekisteri.integration.henkilo._
import fi.vm.sade.hakurekisteri.integration.koodisto.{GetKoodistoKoodiArvot, KoodistoKoodiArvot}
import fi.vm.sade.hakurekisteri.integration.organisaatio.{Oppilaitos, OppilaitosResponse}
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.{yksilollistaminen, Suoritus, SuoritusQuery, VirallinenSuoritus}
import fi.vm.sade.hakurekisteri.tools.RicherString._
import org.joda.time.{DateTime, LocalDate}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.Node

case class HenkiloNotFoundException(oid: String) extends Exception(s"henkilo not found with oid $oid")

class ArvosanatProcessing(organisaatioActor: ActorRef, henkiloActor: ActorRef, suoritusrekisteri: ActorRef, arvosanarekisteri: ActorRef, importBatchActor: ActorRef, koodistoActor: ActorRef, oids: Oids)(implicit val system: ActorSystem) {
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = 15.minutes

  def process(batch: ImportBatch): Future[ImportBatch with Identified[UUID]] = {
    fetchOppiaineetKoodisto() flatMap
      importArvosanat(batch) flatMap
      saveDoneBatch(batch) recoverWith
      saveFailedBatch(batch)
  }

  private def saveFailedBatch(batch: ImportBatch): PartialFunction[Throwable, Future[ImportBatch with Identified[UUID]]] = {
    case t: Throwable => (importBatchActor ? batch.copy(status = batch.status.copy(
      processedTime = Some(new DateTime()),
      savedReferences = None,
      totalRows = None,
      successRows = None,
      failureRows = None,
      messages = Map("virhe" -> Set(t.toString))
    ), state = BatchState.FAILED)).mapTo[ImportBatch with Identified[UUID]]
  }

  private def saveDoneBatch(batch: ImportBatch)(statukset: Seq[ImportArvosanaStatus]): Future[ImportBatch with Identified[UUID]] = {
    val refs = extractReferences(statukset)
    val b = batch.copy(status = batch.status.copy(
      processedTime = Some(new DateTime()),
      savedReferences = Some(refs),
      totalRows = Some(statukset.size),
      successRows = Some(refs.size),
      failureRows = Some(statukset.size - refs.size),
      messages = extractMessages(statukset)
    ), state = BatchState.DONE)

    (importBatchActor ? b).mapTo[ImportBatch with Identified[UUID]]
  }

  private def importArvosanat(batch: ImportBatch)(oppiaineet: Seq[String]): Future[Seq[ImportArvosanaStatus]] =
    Future.sequence(processBatch(batch)(oppiaineet))

  private def extractMessages(statukset: Seq[ImportArvosanaStatus]): Map[String, Set[String]] = statukset.collect {
    case FailureStatus(tunniste, errors) => tunniste -> errors.map(_.toString).toSet
  }.toMap

  private def extractReferences(statukset: Seq[ImportArvosanaStatus]): Map[String, Map[String, String]] = statukset.collect {
    case OkStatus(tunniste, refs) => tunniste -> refs.map(t => t._1.toString -> t._2.map(_.toString).mkString(", ")).toMap
  }.toMap

  private def fetchOppiaineetKoodisto(): Future[Seq[String]] =
    (koodistoActor ? GetKoodistoKoodiArvot("oppiaineetyleissivistava")).
      mapTo[KoodistoKoodiArvot].
      map(arvot => arvot.arvot)

  private def saveArvosana(batchSource: String, suoritusId: UUID, arvosana: ImportArvosana): Future[Arvosana with Identified[UUID]] =
    (arvosanarekisteri ? toArvosana(arvosana)(suoritusId)(batchSource)).mapTo[Arvosana with Identified[UUID]]

  private def saveTodistus(batch: ImportBatch,
                           henkilo: (String, String, Seq[(ImportTodistus, String)]),
                           myonnettyTodistus: (Valmistunut, String)): Future[Seq[ArvosanaStatus]] = {
    val (henkiloTunniste, henkiloOid, _) = henkilo
    val (todistus, myontajaOid) = myonnettyTodistus
    fetchSuoritus(henkiloOid, todistus, myontajaOid, batch.source).flatMap(suoritus =>
      Future.traverse(todistus.arvosanat)(arvosana =>
        saveArvosana(batch.source, suoritus.id, arvosana).map(storedArvosana =>
          OkArvosanaStatus(storedArvosana.id, storedArvosana.suoritus, henkiloTunniste)
        ).recover {
          case t: Throwable => FailureArvosanaStatus(henkiloTunniste, t)
        })
    ).recover {
      case t: Throwable => Seq(FailureArvosanaStatus(henkiloTunniste, t))
    }
  }

  private def processBatch(batch: ImportBatch)(oppiaineet: Seq[String]): Seq[Future[ImportArvosanaStatus]] =
    for (henkilot <- enrich(parseData(batch)(oppiaineet))(batch.source)) yield henkilot.flatMap(henkilo => {
      val (tunniste, _, todistukset) = henkilo
      val statuses = todistukset.collect {
        case (todistus: Valmistunut, myontajaOid: String)
        => saveTodistus(batch, henkilo, (todistus, myontajaOid))
        case (todistus: Siirtynyt, myontajaOid: String)
        => Future.successful(Seq(FailureArvosanaStatus(tunniste, new RuntimeException("siirtynyt suoritus not implemented"))))
        case (todistus: Keskeytynyt, myontajaOid: String)
        => Future.successful(Seq(FailureArvosanaStatus(tunniste, new RuntimeException("keskeytynyt suoritus not implemented"))))
      }

      Future.sequence(statuses).map(tods => {
        val arvosanaStatukset = tods.foldLeft[Seq[ArvosanaStatus]](Seq())(_ ++ _)
        arvosanaStatukset.find(_.isInstanceOf[FailureArvosanaStatus]) match {
          case Some(FailureArvosanaStatus(tunniste, _)) =>
            FailureStatus(tunniste, arvosanaStatukset.filter(_.isInstanceOf[FailureArvosanaStatus]).asInstanceOf[Seq[FailureArvosanaStatus]].map(_.t))
          case _ =>
            OkStatus(henkilo._1, arvosanaStatukset.asInstanceOf[Seq[OkArvosanaStatus]].groupBy(_.suoritus).map(t => t._1 -> t._2.map(_.id)))
        }
      })
    })

  private trait ArvosanaStatus
  private case class OkArvosanaStatus(id: UUID, suoritus: UUID, tunniste: String) extends ArvosanaStatus
  private case class FailureArvosanaStatus(tunniste: String, t: Throwable) extends ArvosanaStatus

  private def toArvosana(arvosana: ImportArvosana)(suoritus: UUID)(source: String): Arvosana =
    Arvosana(suoritus, Arvio410(arvosana.arvosana), arvosana.aine, arvosana.lisatieto, arvosana.valinnainen, None, source, arvosana.jarjestys)

  private def fetchSuoritus(henkiloOid: String, todistus: ImportTodistus, oppilaitosOid: String, lahde: String): Future[Suoritus with Identified[UUID]] =
    (suoritusrekisteri ? SuoritusQuery(henkilo = Some(henkiloOid), myontaja = Some(oppilaitosOid))).
      mapTo[Seq[Suoritus with Identified[UUID]]].
      map(_.find(matchSuoritus(todistus))).
      flatMap {
        case Some(s) => Future.successful(s)
        case None if todistus.komo == oids.lukioKomoOid => createLukioSuoritus(henkiloOid, todistus, oppilaitosOid, lahde)
        case None => Future.failed(SuoritusNotFoundException(henkiloOid, oppilaitosOid, todistus.komo, todistus.valmistuminen))
      }

  private def createLukioSuoritus(henkiloOid: String, todistus: ImportTodistus, oppilaitosOid: String, lahde: String): Future[Suoritus with Identified[UUID]] =
    (suoritusrekisteri ? VirallinenSuoritus(todistus.komo, oppilaitosOid, "KESKEN", todistus.valmistuminen, henkiloOid, yksilollistaminen.Ei, todistus.suoritusKieli, None, vahv = true, lahde)).mapTo[Suoritus with Identified[UUID]]

  private def matchSuoritus(todistus: ImportTodistus)(suoritus: Suoritus): Boolean = suoritus match {
    case s: VirallinenSuoritus => todistus.komo == s.komo && todistus.valmistuminen == s.valmistuminen
    case _ => false
  }

  private def saveHenkilo(henkilo: ImportArvosanaHenkilo)(lahde: String)(tunniste: String) = {
    val vuosi = new LocalDate() match {
      case v if v.getMonthOfYear > 6 => v.getYear + 1
      case v => v.getYear
    }
    SaveHenkilo(
      CreateHenkilo(
        sukunimi = henkilo.sukunimi,
        etunimet = henkilo.etunimet,
        kutsumanimi = henkilo.kutsumanimi,
        hetu = henkilo.tunniste match {
          case ImportHetu(hetu) => Some(hetu)
          case _ => None
        },
        oidHenkilo = henkilo.tunniste match {
          case ImportOppijanumero(oid) => Some(oid)
          case _ => None
        },
        externalId = henkilo.tunniste match {
          case ImportHenkilonTunniste(t, sa, _) => Some(s"${henkilo.todistukset.map(_.myontaja).toSet.toList.sorted.mkString(",")}_${vuosi}_${t}_$sa")
          case _ => None
        },
        henkiloTyyppi = "OPPIJA",
        kasittelijaOid = lahde,
        organisaatioHenkilo = Seq() // TODO liitetäänkö organisaatioihin todistusten myöntäjien perusteella?
      ),
      tunniste
    )
  }

  private def enrich(henkilot: Map[String, ImportArvosanaHenkilo])(lahde: String): Seq[Future[(String, String, Seq[(ImportTodistus, String)])]] = {
    val enriched = for ((tunniste, arvosanahenkilo) <- henkilot) yield
      for (henkilo <- (henkiloActor ? saveHenkilo(arvosanahenkilo)(lahde)(tunniste)).mapTo[SavedHenkilo]) yield {
        val todistukset = for (todistus: ImportTodistus <- arvosanahenkilo.todistukset) yield
          for (oppilaitos <- (organisaatioActor ? Oppilaitos(todistus.myontaja)).mapTo[OppilaitosResponse]) yield (todistus, oppilaitos.oppilaitos.oid)

        (tunniste, henkilo.henkiloOid, todistukset)
      }

    enriched.map(_.flatMap(h => Future.sequence(h._3).map(tods => (h._1, h._2, tods)))).toSeq
  }

  private def parseData(batch: ImportBatch)(oppiaineet: Seq[String]): Map[String, ImportArvosanaHenkilo] =
    (batch.data \ "henkilot" \ "henkilo").map(ImportArvosanaHenkilo(_)(batch.source)(oppiaineet)).groupBy(_.tunniste.tunniste).mapValues(_.head)

  case class SuoritusNotFoundException(henkiloOid: String,
                                       oppilaitosOid: String,
                                       komo: String,
                                       valmistuminen: LocalDate)
    extends Exception(s"Suoritus not found for henkilo $henkiloOid by myontaja $oppilaitosOid with komo $komo and valmistuminen $valmistuminen.")
  object HenkiloTunnisteNotSupportedException extends Exception("henkilo tunniste not yet supported in arvosana batch")
  case class ImportArvosana(aine: String, arvosana: String, lisatieto: Option[String], valinnainen: Boolean, jarjestys: Option[Int] = None)

  sealed trait ImportTodistus {
    val komo: String
    val myontaja: String
    val suoritusKieli: String
    val valmistuminen: LocalDate
  }
  case class Valmistunut(komo: String, myontaja: String, suoritusKieli: String, valmistuminen: LocalDate, arvosanat: Seq[ImportArvosana]) extends ImportTodistus
  case class Siirtynyt(komo: String, myontaja: String, suoritusKieli: String, odotettuValmistuminen: LocalDate) extends ImportTodistus {
    override val valmistuminen = odotettuValmistuminen
  }
  case class Keskeytynyt(komo: String, myontaja: String, suoritusKieli: String, opetusPaattynyt: LocalDate) extends ImportTodistus {
    override  val valmistuminen = opetusPaattynyt
  }
  case class ImportArvosanaHenkilo(tunniste: ImportTunniste, sukunimi: String, etunimet: String, kutsumanimi: String, todistukset: Seq[ImportTodistus])
  object ImportArvosanaHenkilo {
    def getField(name: String)(h: Node): String = (h \ name).head.text
    def getOptionField(name: String)(h: Node): Option[String] = (h \ name).headOption.flatMap(_.text.blankOption)

    def arvosanat(h: Node)(oppiaineet: Seq[String]): Seq[ImportArvosana] = oppiaineet.map(name => (h \ name).headOption.collect {
      case s =>
        val lisatieto = name match {
          case "AI" => Some(getField("tyyppi")(s))
          case _ => getOptionField("kieli")(s)
        }
        (s \ "valinnainen").zipWithIndex.
          map(t => ImportArvosana(name, t._1.text, lisatieto, valinnainen = true, Some(t._2))) :+ ImportArvosana(name, getField("yhteinen")(s), lisatieto, valinnainen = false)
    }).flatten.foldLeft[Seq[ImportArvosana]](Seq())(_ ++ _)

    def valmistunutSuoritus(s: Node) = (s \ "valmistuminen").nonEmpty
    def luokalleJaanytSuoritus(s: Node) = (s \ "valmistuminensiirtyy").nonEmpty
    def keskeytynytSuoritus(s: Node) = (s \ "eivalmistu").nonEmpty

    def todistus(name: String, komoOid: String, oppijanumero: Option[String])(h: Node)(lahde: String)(oppiaineet: Seq[String]): Option[ImportTodistus] = (h \ name).headOption.map(s => {
      val myontaja = getField("myontaja")(s)
      val suoritusKieli = getField("suorituskieli")(s)
      if (valmistunutSuoritus(s)) {
        Valmistunut(komoOid, myontaja, suoritusKieli, new LocalDate(getField("valmistuminen")(s)), arvosanat(s)(oppiaineet))
      } else if (luokalleJaanytSuoritus(s)) {
        Siirtynyt(komoOid, myontaja, suoritusKieli, new LocalDate(getField("oletettuvalmistuminen")(s)))
      } else if (keskeytynytSuoritus(s)) {
        Keskeytynyt(komoOid, myontaja, suoritusKieli, new LocalDate(getField("opetuspaattynyt")(s)))
      } else {
        throw new RuntimeException("unrecognized suoritus")
      }
    })

    val tyypit = Map(
      "perusopetus" -> oids.perusopetusKomoOid,
      "perusopetuksenlisaopetus" -> oids.lisaopetusKomoOid,
      "ammattistartti" -> oids.ammattistarttiKomoOid,
      "valmentava" -> oids.valmentavaKomoOid,
      "maahanmuuttajienlukioonvalmistava" -> oids.lukioonvalmistavaKomoOid,
      "maahanmuuttajienammvalmistava" -> oids.ammatilliseenvalmistavaKomoOid,
      "ulkomainen" -> oids.ulkomainenkorvaavaKomoOid,
      "lukio" -> oids.lukioKomoOid,
      "ammatillinen" -> oids.ammatillinenKomoOid
    )

    def apply(h: Node)(lahde: String)(oppiaineet: Seq[String]): ImportArvosanaHenkilo = {
      val hetu = getOptionField("hetu")(h)
      val oppijanumero = getOptionField("oppijanumero")(h)
      val henkiloTunniste = getOptionField("henkiloTunniste")(h)
      val syntymaAika = getOptionField("syntymaAika")(h)

      val tunniste = (hetu, oppijanumero, henkiloTunniste, syntymaAika) match {
        case (Some(henkilotunnus), _, _, _) => ImportHetu(henkilotunnus)
        case (_, Some(o), _, _) => ImportOppijanumero(o)
        case (_, _, Some(t), Some(sa)) => ImportHenkilonTunniste(t, sa, "0")
        case t =>
          throw new IllegalArgumentException(s"henkilo could not be identified: hetu, oppijanumero or henkiloTunniste+syntymaAika missing $t")
      }

      val sukunimi = getField("sukunimi")(h)
      val etunimet = getField("etunimet")(h)
      val kutsumanimi = getField("kutsumanimi")(h)

      val todistuksetNode = (h \ "todistukset").head
      val todistukset = tyypit.map(t => todistus(t._1, t._2, oppijanumero)(todistuksetNode)(lahde)(oppiaineet)).toSeq.flatten

      ImportArvosanaHenkilo(
        tunniste = tunniste,
        sukunimi = sukunimi,
        etunimet = etunimet,
        kutsumanimi = kutsumanimi,
        todistukset = todistukset
      )
    }
  }
  trait ImportArvosanaStatus {
    val tunniste: String
  }
  case class OkStatus(tunniste: String, todistukset: Map[UUID, Seq[UUID]]) extends ImportArvosanaStatus
  case class FailureStatus(tunniste: String, errors: Seq[Throwable]) extends ImportArvosanaStatus
}

