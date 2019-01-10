package fi.vm.sade.hakurekisteri.batchimport

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Oids
import fi.vm.sade.hakurekisteri.arvosana.{Arvio410, Arvosana}
import fi.vm.sade.hakurekisteri.integration.henkilo._
import fi.vm.sade.hakurekisteri.integration.koodisto.{GetKoodistoKoodiArvot, KoodistoActorRef, KoodistoKoodiArvot}
import fi.vm.sade.hakurekisteri.integration.organisaatio.{Oppilaitos, OppilaitosResponse, OrganisaatioActorRef}
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.{SuoritusQuery, VirallinenSuoritus, yksilollistaminen}
import fi.vm.sade.hakurekisteri.tools.RicherString._
import org.joda.time.{DateTime, LocalDate}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.Node

case class HenkiloNotFoundException(oid: String) extends Exception(s"henkilo not found with oid $oid")

class ArvosanatProcessing(importBatchOrgActor: ActorRef, organisaatioActor: OrganisaatioActorRef, henkiloActor: HenkiloActorRef, suoritusrekisteri: ActorRef, arvosanarekisteri: ActorRef, importBatchActor: ActorRef, koodistoActor: KoodistoActorRef)(implicit val system: ActorSystem) {
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = 1.hour

  def process(batch: ImportBatch): Future[ImportBatch with Identified[UUID]] = {
    fetchOppiaineetKoodisto() flatMap importArvosanat(batch) flatMap(organisaatiotJaArvosanat => {
      val organisaatiot = organisaatiotJaArvosanat.flatMap(_._1).toSet
      val arvosanat = organisaatiotJaArvosanat.map(_._2)

      (saveDoneBatch(batch)(arvosanat) recoverWith saveFailedBatch(batch)) flatMap saveBatchOrganisaatiot(organisaatiot)
    })
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
  private def saveBatchOrganisaatiot(organisaatiot: Set[String])(batch: ImportBatch with Identified[UUID]): Future[ImportBatch with Identified[UUID]] = {
    val resourceId = batch.id
    importBatchOrgActor ! ImportBatchOrgs(resourceId, organisaatiot)
    Future.successful(batch)
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

  private def importArvosanat(batch: ImportBatch)(oppiaineet: Seq[String]): Future[Seq[(Seq[String], ImportArvosanaStatus)]] =
    Future.sequence(processBatch(batch)(oppiaineet))

  private def extractMessages(statukset: Seq[ImportArvosanaStatus]): Map[String, Set[String]] = statukset.collect {
    case FailureStatus(tunniste, errors) => tunniste -> errors.map(_.toString).toSet
  }.toMap

  private def extractReferences(statukset: Seq[ImportArvosanaStatus]): Map[String, Map[String, String]] = statukset.collect {
    case OkStatus(tunniste, refs) => tunniste -> refs.map(t => t._1.toString -> t._2.map(_.toString).mkString(", ")).toMap
  }.toMap

  private def fetchOppiaineetKoodisto(): Future[Seq[String]] =
    (koodistoActor.actor ? GetKoodistoKoodiArvot("oppiaineetyleissivistava")).
      mapTo[KoodistoKoodiArvot].
      map(arvot => arvot.arvot)

  private def updateSuoritusValmis(suoritus: VirallinenSuoritus with Identified[UUID],
                                   valmistuminen: LocalDate): Future[VirallinenSuoritus with Identified[UUID]] =
    (suoritusrekisteri ? suoritus.copy(tila = "VALMIS", valmistuminen = valmistuminen)).mapTo[VirallinenSuoritus with Identified[UUID]]

  private def updateSuoritusSiirtynyt(suoritus: VirallinenSuoritus with Identified[UUID],
                                      odotettuValmistuminen: LocalDate): Future[VirallinenSuoritus with Identified[UUID]] =
    (suoritusrekisteri ? suoritus.copy(tila = "KESKEN", valmistuminen = odotettuValmistuminen)).mapTo[VirallinenSuoritus with Identified[UUID]]

  private def updateSuoritusKeskeytynyt(suoritus: VirallinenSuoritus with Identified[UUID],
                                        opetusPaattynyt: LocalDate): Future[VirallinenSuoritus with Identified[UUID]] =
    (suoritusrekisteri ? suoritus.copy(tila = "KESKEYTYNYT", valmistuminen = opetusPaattynyt)).mapTo[VirallinenSuoritus with Identified[UUID]]

  private def saveArvosana(batchSource: String, suoritusId: UUID, arvosana: ImportArvosana): Future[Arvosana with Identified[UUID]] =
    (arvosanarekisteri ? toArvosana(arvosana)(suoritusId)(batchSource)).mapTo[Arvosana with Identified[UUID]]

  private def saveArvosanat(batch: ImportBatch,
                            henkilotunniste: String,
                            suoritus: VirallinenSuoritus with Identified[UUID],
                            todistus: WithArvosanat): Future[Seq[ArvosanaStatus]] =
    Future.traverse(todistus.arvosanat)(arvosana => {
      Thread.sleep(50L)
      saveArvosana(batch.source, suoritus.id, arvosana) map (storedArvosana =>
        OkArvosanaStatus(storedArvosana.id, storedArvosana.suoritus, henkilotunniste)
        ) recover { case t: Throwable => FailureArvosanaStatus(henkilotunniste, t) }
    })

  private def processTodistus(batch: ImportBatch,
                              todistus: ImportTodistus,
                              myontaja: String,
                              henkilotunniste: String,
                              henkiloOid: String): Future[Seq[ArvosanaStatus]] =
    fetchSuoritus(henkiloOid, todistus, myontaja, batch.source) flatMap (suoritus =>
      todistus match {
        case t: Valmistunut => updateSuoritusValmis(suoritus, t.valmistuminen) flatMap (suoritus =>
          saveArvosanat(batch, henkilotunniste, suoritus, t)
          )
        case t: Siirtynyt => updateSuoritusSiirtynyt(suoritus, t.odotettuValmistuminen) map (_ => Seq())
        case t: Keskeytynyt => updateSuoritusKeskeytynyt(suoritus, t.opetusPaattynyt) flatMap (suoritus =>
          saveArvosanat(batch, henkilotunniste, suoritus, t)
          )
      }) recover { case t: Throwable => Seq(FailureArvosanaStatus(henkilotunniste, t)) }

  private def processHenkilo(batch: ImportBatch,
                             henkilotunniste: String,
                             henkiloOid: String,
                             todistukset: Seq[(ImportTodistus, String)]): Future[ImportArvosanaStatus] =
    Future.traverse(todistukset) {
      case (todistus, myontaja) => processTodistus(batch, todistus, myontaja, henkilotunniste, henkiloOid)
    } map (_.flatten[ArvosanaStatus]) map (statuses =>
      statuses.find(_.isInstanceOf[FailureArvosanaStatus]) match {
        case Some(FailureArvosanaStatus(tunniste, _)) =>
          FailureStatus(tunniste, statuses collect { case FailureArvosanaStatus(_, t) => t })
        case _ =>
          OkStatus(henkilotunniste, statuses.asInstanceOf[Seq[OkArvosanaStatus]] groupBy (_.suoritus) mapValues (_ map (_.id)))
      }
    )

  private def processBatch(batch: ImportBatch)(oppiaineet: Seq[String]): Seq[Future[(Seq[String], ImportArvosanaStatus)]] = {
    val parsedBatch = parseData(batch)(oppiaineet)
    val myontajat = parsedBatch.values.flatMap(_.todistukset.map(_.myontaja)).toSet
    for (henkilo <- enrich(parsedBatch)(batch.source)) yield henkilo flatMap {
      case (henkilotunniste, henkiloOid, todistukset) =>
        Thread.sleep(50L)
        val orgs = todistukset.map(_._2)
        processHenkilo(batch, henkilotunniste, henkiloOid, todistukset).flatMap(statukset => Future.successful((orgs, statukset)))
    }
  }

  private trait ArvosanaStatus
  private case class OkArvosanaStatus(id: UUID, suoritus: UUID, tunniste: String) extends ArvosanaStatus
  private case class FailureArvosanaStatus(tunniste: String, t: Throwable) extends ArvosanaStatus

  private def toArvosana(arvosana: ImportArvosana)(suoritus: UUID)(source: String): Arvosana =
    Arvosana(suoritus, Arvio410(arvosana.arvosana), arvosana.aine, arvosana.lisatieto, arvosana.valinnainen, None, source, Map(), arvosana.jarjestys)

  private def fetchSuoritus(henkiloOid: String, todistus: ImportTodistus, oppilaitosOid: String, lahde: String): Future[VirallinenSuoritus with Identified[UUID]] =
    (suoritusrekisteri ? SuoritusQuery(henkilo = Some(henkiloOid), myontaja = Some(oppilaitosOid), komo = Some(todistus.komo))).
      mapTo[Seq[VirallinenSuoritus with Identified[UUID]]].
      flatMap(suoritukset => suoritukset.headOption match {
      case Some(suoritus) if suoritukset.length == 1 => Future.successful(suoritus)
      case Some(_) if suoritukset.length > 1 => Future.failed(new MultipleSuoritusException(henkiloOid, oppilaitosOid, todistus.komo))
      case None if todistus.komo == Oids.lukioKomoOid => createLukioSuoritus(henkiloOid, todistus, oppilaitosOid, lahde)
      case None => Future.failed(new SuoritusNotFoundException(henkiloOid, oppilaitosOid, todistus.komo))
    })

  private def createLukioSuoritus(henkiloOid: String, todistus: ImportTodistus, oppilaitosOid: String, lahde: String): Future[VirallinenSuoritus with Identified[UUID]] =
    (suoritusrekisteri ? VirallinenSuoritus(
      todistus.komo,
      oppilaitosOid, "KESKEN",
      todistus.valmistuminen,
      henkiloOid,
      yksilollistaminen.Ei,
      todistus.suoritusKieli,
      None,
      vahv = true,
      lahde)).mapTo[VirallinenSuoritus with Identified[UUID]]

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
        externalIds = henkilo.tunniste match {
          case ImportHenkilonTunniste(t, sa, _) => Some(Seq(s"${henkilo.todistukset.map(_.myontaja).toSet.toList.sorted.mkString(",")}_${vuosi}_${t}_$sa"))
          case _ => None
        },
        henkiloTyyppi = "OPPIJA"
      ),
      tunniste
    )
  }

  private def enrich(henkilot: Map[String, ImportArvosanaHenkilo])(lahde: String): Seq[Future[(String, String, Seq[(ImportTodistus, String)])]] = {
    val enriched = for ((tunniste, arvosanahenkilo) <- henkilot) yield
      for (henkilo <- (henkiloActor.actor ? saveHenkilo(arvosanahenkilo)(lahde)(tunniste)).mapTo[SavedHenkilo]) yield {
        val todistukset = for (todistus: ImportTodistus <- arvosanahenkilo.todistukset) yield
          for (oppilaitos <- (organisaatioActor.actor ? Oppilaitos(todistus.myontaja)).mapTo[OppilaitosResponse]) yield (todistus, oppilaitos.oppilaitos.oid)

        (tunniste, henkilo.henkiloOid, todistukset)
      }

    enriched.map(_.flatMap(h => Future.sequence(h._3).map(tods => (h._1, h._2, tods)))).toSeq
  }

  private def parseData(batch: ImportBatch)(oppiaineet: Seq[String]): Map[String, ImportArvosanaHenkilo] =
    (batch.data \ "henkilot" \ "henkilo").map(ImportArvosanaHenkilo(_)(batch.source)(oppiaineet)).groupBy(_.tunniste.tunniste).mapValues(_.head)

  case class SuoritusNotFoundException(henkiloOid: String,
                                       myontaja: String,
                                       komo: String)
    extends Exception(s"Suoritus not found for henkilo $henkiloOid by myontaja $myontaja with komo $komo.")

  case class MultipleSuoritusException(henkiloOid: String,
                                       myontaja: String,
                                       komo: String)
    extends Exception(s"Multiple suoritus found for henkilo $henkiloOid by myontaja $myontaja with komo $komo.")

  object HenkiloTunnisteNotSupportedException extends Exception("henkilo tunniste not yet supported in arvosana batch")
  case class ImportArvosana(aine: String, arvosana: String, lisatieto: Option[String], valinnainen: Boolean, jarjestys: Option[Int] = None)

  sealed trait ImportTodistus {
    val komo: String
    val myontaja: String
    val suoritusKieli: String
    val valmistuminen: LocalDate
  }
  sealed trait WithArvosanat extends ImportTodistus {
    val arvosanat: Seq[ImportArvosana]
  }
  case class Valmistunut(komo: String, myontaja: String, suoritusKieli: String, valmistuminen: LocalDate, arvosanat: Seq[ImportArvosana]) extends WithArvosanat
  case class Siirtynyt(komo: String, myontaja: String, suoritusKieli: String, odotettuValmistuminen: LocalDate) extends ImportTodistus {
    override val valmistuminen = odotettuValmistuminen
  }
  case class Keskeytynyt(komo: String, myontaja: String, suoritusKieli: String, opetusPaattynyt: LocalDate, arvosanat: Seq[ImportArvosana]) extends WithArvosanat {
    override val valmistuminen = opetusPaattynyt
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
      if (keskeytynytSuoritus(s)) {
        val valmistunutPvm = (s \ "opetuspaattynyt") ++ (s \ "valmistuminen") match {
          case valm if valm.nonEmpty => new LocalDate(valm.head.text)
          case _ => throw new RuntimeException("value for eivalmistu given, but no value in opetuspaattynyt or valmistuminen")
        }
        if (name == "perusopetuksenlisaopetus")
          Keskeytynyt(komoOid, myontaja, suoritusKieli, valmistunutPvm, arvosanat(s)(oppiaineet))
        else
          Keskeytynyt(komoOid, myontaja, suoritusKieli, valmistunutPvm, Seq())
      } else if (luokalleJaanytSuoritus(s)) {
        Siirtynyt(komoOid, myontaja, suoritusKieli, new LocalDate(getField("oletettuvalmistuminen")(s)))
      } else if (valmistunutSuoritus(s)) {
        Valmistunut(komoOid, myontaja, suoritusKieli, new LocalDate(getField("valmistuminen")(s)), arvosanat(s)(oppiaineet))
      } else {
        throw new RuntimeException("unrecognized suoritus")
      }
    })

    val tyypit = Map(
      "perusopetus"                       -> Oids.perusopetusKomoOid,
      "perusopetuksenlisaopetus"          -> Oids.lisaopetusKomoOid,
      "ammattistartti"                    -> Oids.ammattistarttiKomoOid,
      "valmentava"                        -> Oids.valmentavaKomoOid,
      "maahanmuuttajienlukioonvalmistava" -> Oids.lukioonvalmistavaKomoOid,
      "maahanmuuttajienammvalmistava"     -> Oids.ammatilliseenvalmistavaKomoOid,
      "valma"                             -> Oids.valmaKomoOid,
      "telma"                             -> Oids.telmaKomoOid,
      "ulkomainen"                        -> Oids.ulkomainenkorvaavaKomoOid,
      "lukio"                             -> Oids.lukioKomoOid,
      "ammatillinen"                      -> Oids.ammatillinenKomoOid,
      "ammatillinentutkinto"              -> Oids.ammatillinentutkintoKomoOid,
      "erikoisammattitutkinto"            -> Oids.erikoisammattitutkintoKomoOid
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

