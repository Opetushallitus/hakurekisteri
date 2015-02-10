package fi.vm.sade.hakurekisteri.batchimport

import java.util.UUID

import akka.actor.{ActorSystem, ActorRef}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.arvosana.{Arvio410, Arvosana}
import fi.vm.sade.hakurekisteri.integration.henkilo._
import fi.vm.sade.hakurekisteri.integration.koodisto.{KoodistoKoodiArvot, GetKoodistoKoodiArvot}
import fi.vm.sade.hakurekisteri.integration.organisaatio.{Oppilaitos, OppilaitosResponse}
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.{yksilollistaminen, Suoritus, SuoritusQuery, VirallinenSuoritus}
import org.joda.time.{DateTime, LocalDate}
import scala.collection.immutable.Iterable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.xml.Node

class ArvosanatProcessing(organisaatioActor: ActorRef, henkiloActor: ActorRef, suoritusrekisteri: ActorRef, arvosanarekisteri: ActorRef, importBatchActor: ActorRef, koodistoActor: ActorRef)(implicit val system: ActorSystem) {
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = 10.minutes

  def process(batch: ImportBatch): Future[ImportBatch with Identified[UUID]] = {
    (for (
      statukset <- processBatch(batch)
    ) yield {
      val refs = savedRefs(statukset)
      batch.copy(status = batch.status.copy(
        processedTime = Some(new DateTime()),
        savedReferences = Some(refs),
        totalRows = Some(statukset.size),
        successRows = Some(refs.size),
        failureRows = Some(statukset.size - refs.size),
        messages = messages(statukset)
      ), state = BatchState.DONE)
    }).flatMap(b => (importBatchActor ? b).mapTo[ImportBatch with Identified[UUID]]).recoverWith {
      case t: Throwable => (importBatchActor ? batch.copy(status = batch.status.copy(
        processedTime = Some(new DateTime()),
        messages = Map("virhe käsittelyssä" -> Set(t.toString))
      ), state = BatchState.FAILED)).mapTo[ImportBatch with Identified[UUID]]
    }
  }

  private def messages(statukset: Seq[ImportArvosanaStatus]): Map[String, Set[String]] = statukset.collect {
    case FailureStatus(tunniste, t) => tunniste -> Set(t.toString)
  }.toMap

  private def savedRefs(statukset: Seq[ImportArvosanaStatus]): Map[String, Map[String, String]] = statukset.collect {
    case OkStatus(tunniste, refs) => tunniste -> refs.map(t => t._1.toString -> t._2.map(_.toString).mkString(", ")).toMap
  }.toMap

  private def fetchOppiaineetKoodisto(): Future[Seq[String]] = {
    (koodistoActor ? GetKoodistoKoodiArvot("oppiaineetyleissivistava")).mapTo[KoodistoKoodiArvot].map(arvot => arvot.arvot)
  }

  private def processBatch(batch: ImportBatch): Future[Seq[ImportArvosanaStatus]] = {
    (for (
      oppiaineet <- fetchOppiaineetKoodisto();
      hlos <- enrich(parseData(batch)(oppiaineet))
    ) yield for (
        h <- hlos; t <- h._3
      ) yield (for (
          s <- fetchSuoritus(h._2, t._1, t._2)
        ) yield for (
            a <- t._1.arvosanat
          ) yield (arvosanarekisteri ? toArvosana(a)(s.id)(batch.source)).mapTo[Arvosana with Identified[UUID]]).flatMap(Future.sequence(_)).map(arvosanat => {
            OkStatus(h._1, arvosanat.groupBy(_.suoritus).map(t => (t._1, t._2.map(_.id))))
          }).recoverWith {
            case t: Throwable => Future.successful(FailureStatus(h._1, t))
          }).flatMap(Future.sequence(_))
  }

  private def toArvosana(arvosana: ImportArvosana)(suoritus: UUID)(source: String): Arvosana =
    Arvosana(suoritus, Arvio410(arvosana.arvosana), arvosana.aine, arvosana.lisatieto, arvosana.valinnainen, None, source)

  private def fetchSuoritus(henkiloOid: String, todistus: ImportTodistus, oppilaitosOid: String): Future[Suoritus with Identified[UUID]] = {
    (suoritusrekisteri ? SuoritusQuery(henkilo = Some(henkiloOid), myontaja = Some(oppilaitosOid))).mapTo[Seq[Suoritus with Identified[UUID]]].map(_.find(matchSuoritus(todistus))).map {
      case Some(s) => s
      case None => throw SuoritusNotFoundException(henkiloOid, todistus, oppilaitosOid)
    }
  }

  private def matchSuoritus(todistus: ImportTodistus)(suoritus: Suoritus): Boolean = (todistus, suoritus) match {
    case (ImportTodistus(Config.perusopetusKomoOid, _, _, v), s: VirallinenSuoritus) if s.komo == Config.perusopetusKomoOid && s.valmistuminen == v => true
    case (ImportTodistus(Config.lisaopetusKomoOid, _, _, v), s: VirallinenSuoritus) if s.komo == Config.lisaopetusKomoOid && s.valmistuminen == v => true
    case _ => false
  }

  private def enrich(henkilot: Map[String, ImportArvosanaHenkilo]): Future[Seq[(String, String, Seq[(ImportTodistus, String)])]] = {
    val enriched: Iterable[Future[(String, String, Seq[Future[(ImportTodistus, String)]])]] = for (
      (tunniste: String, henkilo: ImportArvosanaHenkilo) <- henkilot
    ) yield {
      val q: HenkiloQuery = henkilo.tunniste match {
        case ImportHetu(hetu) => HenkiloQuery(None, Some(hetu), tunniste)
        case ImportOppijanumero(oid) => HenkiloQuery(Some(oid), None, tunniste)
        case ImportHenkilonTunniste(_, _, _) => throw HenkiloTunnisteNotSupportedException
      }
      for (
        henk <- (henkiloActor ? q).mapTo[FoundHenkilos]
      ) yield {
        if (henk.henkilot.isEmpty) throw HenkiloNotFoundException(q.oppijanumero.getOrElse(q.hetu.getOrElse("")))
        val todistukset: Seq[Future[(ImportTodistus, String)]] = for (
          todistus: ImportTodistus <- henkilo.todistukset
        ) yield for (
            oppilaitos <- (organisaatioActor ? Oppilaitos(todistus.myontaja)).mapTo[OppilaitosResponse]
          ) yield (todistus, oppilaitos.oppilaitos.oid)

        (tunniste, henk.henkilot.head.oidHenkilo, todistukset)
      }
    }

    Future.sequence(enriched).flatMap(hlos => {
      Future.sequence(hlos.map(h => {
        Future.sequence(h._3).map(tods => (h._1, h._2, tods))
      }).toSeq)
    })
  }

  private def parseData(batch: ImportBatch)(oppiaineet: Seq[String]): Map[String, ImportArvosanaHenkilo] =
    (batch.data \ "henkilot" \ "henkilo").map(ImportArvosanaHenkilo(_)(batch.source)(oppiaineet)).groupBy(_.tunniste.tunniste).mapValues(_.head)

  case class SuoritusNotFoundException(henkiloOid: String, todistus: ImportTodistus, oppilaitosOid: String) extends Exception(s"suoritus not found for henkilo $henkiloOid with myontaja $oppilaitosOid for todistus $todistus")

  object HenkiloTunnisteNotSupportedException extends Exception("henkilo tunniste not yet supported in arvosana batch")

  case class ImportArvosana(aine: String, arvosana: String, lisatieto: Option[String], valinnainen: Boolean)

  case class ImportTodistus(komo: String, myontaja: String, arvosanat: Seq[ImportArvosana], valmistuminen: LocalDate)

  case class ImportArvosanaHenkilo(tunniste: ImportTunniste, todistukset: Seq[ImportTodistus])

  import fi.vm.sade.hakurekisteri.tools.RicherString._

  object ImportArvosanaHenkilo {
    def getField(name: String)(h: Node): String = (h \ name).head.text
    def getOptionField(name: String)(h: Node): Option[String] = (h \ name).headOption.flatMap(_.text.blankOption)
    def arvosanat(h: Node)(oppiaineet: Seq[String]): Seq[ImportArvosana] = {
      oppiaineet.map(name => {
        (h \ name).headOption.collect {
          case s =>
            val lisatieto = name match {
              case "AI" => getOptionField("tyyppi")(s)
              case _ => getOptionField("kieli")(s)
            }
            (s \ "valinnainen").map(a => ImportArvosana(name, a.text, lisatieto, valinnainen = true)) :+ ImportArvosana(name, getField("yhteinen")(s), lisatieto, valinnainen = false)
        }
      }).flatten.foldLeft[Seq[ImportArvosana]](Seq())(_ ++ _)
    }
    def todistus(name: String, komoOid: String, oppijanumero: Option[String])(h: Node)(lahde: String)(oppiaineet: Seq[String]): Option[ImportTodistus] = (h \ name).headOption.map(s => {
      val valmistuminen = getField("valmistuminen")(s)
      val myontaja = getField("myontaja")(s)
      // val suoritusKieli = getField("suoritusKieli")(s)
      ImportTodistus(komoOid, myontaja, arvosanat(s)(oppiaineet), new LocalDate(valmistuminen))
    })

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

      val todistuksetNode = (h \ "todistukset").head

      val todistukset = Seq(
        todistus("perusopetus", Config.perusopetusKomoOid, oppijanumero)(todistuksetNode)(lahde)(oppiaineet),
        todistus("perusopetuksenlisaopetus", Config.lisaopetusKomoOid, oppijanumero)(todistuksetNode)(lahde)(oppiaineet),
        todistus("ammattistartti", Config.ammattistarttiKomoOid, oppijanumero)(todistuksetNode)(lahde)(oppiaineet),
        todistus("valmentava", Config.valmentavaKomoOid, oppijanumero)(todistuksetNode)(lahde)(oppiaineet),
        todistus("maahanmuuttajienlukioonvalmistava", Config.lukioonvalmistavaKomoOid, oppijanumero)(todistuksetNode)(lahde)(oppiaineet),
        todistus("maahanmuuttajienammvalmistava", Config.ammatilliseenvalmistavaKomoOid, oppijanumero)(todistuksetNode)(lahde)(oppiaineet),
        todistus("ulkomainen", Config.ulkomainenkorvaavaKomoOid, oppijanumero)(todistuksetNode)(lahde)(oppiaineet),
        todistus("lukio", Config.lukioKomoOid, oppijanumero)(todistuksetNode)(lahde)(oppiaineet),
        todistus("ammatillinen", Config.ammatillinenKomoOid, oppijanumero)(todistuksetNode)(lahde)(oppiaineet)
      ).flatten

      ImportArvosanaHenkilo(
        tunniste = tunniste,
        todistukset = todistukset
      )
    }
  }

  trait ImportArvosanaStatus {
    val tunniste: String
  }

  case class OkStatus(tunniste: String, todistukset: Map[UUID, Seq[UUID]]) extends ImportArvosanaStatus
  case class FailureStatus(tunniste: String, t: Throwable) extends ImportArvosanaStatus

}

