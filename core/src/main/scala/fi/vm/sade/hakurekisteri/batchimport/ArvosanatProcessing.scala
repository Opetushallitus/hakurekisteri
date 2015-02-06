package fi.vm.sade.hakurekisteri.batchimport

import java.util.UUID

import akka.actor.{ActorSystem, ActorRef}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.arvosana.{Arvio410, Arvosana}
import fi.vm.sade.hakurekisteri.integration.henkilo._
import fi.vm.sade.hakurekisteri.integration.organisaatio.{Oppilaitos, OppilaitosResponse}
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusQuery, VirallinenSuoritus}
import org.joda.time.{DateTime, LocalDate}
import scala.collection.immutable.Iterable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.xml.Node

class ArvosanatProcessing(organisaatioActor: ActorRef, henkiloActor: ActorRef, suoritusrekisteri: ActorRef, arvosanarekisteri: ActorRef, importBatchActor: ActorRef)(implicit val system: ActorSystem) {
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = 10.minutes

  def process(batch: ImportBatch): Future[ImportBatch] = {
    (for (
      statukset <- processBatch(batch)
    ) yield {
      val refs = savedRefs(statukset)
      batch.copy(status = batch.status.copy(
        processedTime = Some(new DateTime()),
        savedReferences = refs,
        totalRows = Some(statukset.size),
        successRows = Some(refs.size),
        failureRows = Some(statukset.size - refs.size),
        messages = messages(statukset)
      ))
    }).flatMap(b => (importBatchActor ? b).mapTo[ImportBatch]).recoverWith {
      case t: Throwable => (importBatchActor ? batch.copy(status = batch.status.copy(
        processedTime = Some(new DateTime()),
        messages = Map("virhe käsittelyssä" -> Set(t.toString))
      ))).mapTo[ImportBatch]
    }
  }

  private def messages(statukset: Seq[ImportArvosanaStatus]): Map[String, Set[String]] = statukset.collect {
    case FailureStatus(tunniste, t) => tunniste -> Set(t.toString)
  }.toMap

  private def savedRefs(statukset: Seq[ImportArvosanaStatus]) = Some(statukset.collect {
    case OkStatus(tunniste, refs) => tunniste -> refs.map(t => t._1.toString -> t._2.map(_.toString).mkString(", ")).toMap
  }.toMap)

  private def processBatch(batch: ImportBatch): Future[Seq[ImportArvosanaStatus]] = {
    val henkilot = parseData(batch)
    val enriched = enrich(henkilot)

    (for (
      hlos <- enriched
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
    Arvosana(suoritus, Arvio410("TODO"), "TODO", Some("TODO"), valinnainen = true, None, source)

  private def fetchSuoritus(henkiloOid: String, todistus: ImportTodistus, oppilaitosOid: String): Future[Suoritus with Identified[UUID]] = {
    (suoritusrekisteri ? SuoritusQuery(henkilo = Some(henkiloOid), myontaja = Some(oppilaitosOid))).mapTo[Seq[Suoritus with Identified[UUID]]].map(_.find(matchSuoritus(todistus))).map {
      case Some(s) => s
      case None => throw SuoritusNotFoundException(henkiloOid, todistus, oppilaitosOid)
    }
  }

  private def matchSuoritus(todistus: ImportTodistus)(suoritus: Suoritus): Boolean = (todistus, suoritus) match {
    case (ImportTodistus(Config.perusopetusKomoOid, _, _, _), s: VirallinenSuoritus) if s.komo == Config.perusopetusKomoOid => true
    case (ImportTodistus(Config.lisaopetusKomoOid, _, _, _), s: VirallinenSuoritus) if s.komo == Config.lisaopetusKomoOid => true
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

  private def parseData(batch: ImportBatch): Map[String, ImportArvosanaHenkilo] = try {
    val henkilot = (batch.data \ "henkilot" \ "henkilo").map(ImportArvosanaHenkilo(_)(batch.source)).groupBy(_.tunniste.tunniste).mapValues(_.head)
    henkilot
  } catch {
    case t: Throwable =>
      //batchFailed(t)
      Map()
  }

  case class SuoritusNotFoundException(henkiloOid: String, todistus: ImportTodistus, oppilaitosOid: String) extends Exception(s"suoritus not found for henkilo $henkiloOid with myontaja $oppilaitosOid for todistus $todistus")

  object HenkiloTunnisteNotSupportedException extends Exception("henkilo tunniste not yet supported in arvosana batch")

  case class ImportArvosana()

  case class ImportTodistus(komo: String, myontaja: String, arvosanat: Seq[ImportArvosana], valmistuminen: LocalDate)

  case class ImportArvosanaHenkilo(tunniste: ImportTunniste, todistukset: Seq[ImportTodistus])

  object ImportArvosanaHenkilo {
    def apply(h: Node)(lahde: String): ImportArvosanaHenkilo = ???
  }

  trait ImportArvosanaStatus {
    val tunniste: String
  }

  case class OkStatus(tunniste: String, todistukset: Map[UUID, Seq[UUID]]) extends ImportArvosanaStatus
  case class FailureStatus(tunniste: String, t: Throwable) extends ImportArvosanaStatus

}

