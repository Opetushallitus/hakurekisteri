package fi.vm.sade.hakurekisteri.oppija

import fi.vm.sade.hakurekisteri.integration.virta.VirtaConnectionErrorException
import fi.vm.sade.hakurekisteri.rest.support.{SpringSecuritySupport, HakurekisteriJsonSupport, Registers}
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import org.scalatra.json.JacksonJsonSupport
import org.scalatra._
import _root_.akka.actor.{ActorSystem, ActorRef}
import scala.concurrent.{Future, ExecutionContext}
import _root_.akka.util.Timeout
import org.scalatra.swagger.Swagger
import fi.vm.sade.hakurekisteri.hakija.HakijaQuery
import fi.vm.sade.hakurekisteri.integration.hakemus.{HakemusQuery, FullHakemus}
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusQuery}
import _root_.akka.pattern.ask
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaQuery}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusQuery}
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, ArvosanaQuery}
import fi.vm.sade.hakurekisteri.storage.Identified
import java.util.UUID
import fi.vm.sade.hakurekisteri.ensikertalainen.{NoHetuException, Ensikertalainen, EnsikertalainenQuery}
import scala.Some
import fi.vm.sade.hakurekisteri.integration.hakemus.HenkiloHakijaQuery


class OppijaResource(rekisterit: Registers, hakemusRekisteri: ActorRef, ensikertalaisuus: ActorRef)(implicit system: ActorSystem, sw: Swagger) extends HakuJaValintarekisteriStack  with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with CorsSupport with SpringSecuritySupport {

  override protected implicit def executor: ExecutionContext = system.dispatcher

  options("/*") {
    response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
  }

  before() {
    contentType = formats("json")
  }

  import scala.concurrent.duration._

  implicit val defaultTimeout: Timeout = 60.seconds


  get("/") {

    import _root_.akka.pattern.ask
    val q = HakijaQuery(params, currentUser)

    new AsyncResult() {
      override implicit def timeout: Duration = 500.seconds
      val is = for (
        hakemukset <- (hakemusRekisteri ? HakemusQuery(q)).mapTo[Seq[FullHakemus]];
        oppijat <- fetchOppijatFor(hakemukset)
      ) yield oppijat
    }
  }

  get("/:oid") {
    val q = HenkiloHakijaQuery(params("oid"))
    new AsyncResult() {
      val is = for (
        hakemukset <- (hakemusRekisteri ? q).mapTo[Seq[FullHakemus]];
        oppijat <- fetchOppijatFor(hakemukset.filter((fh) => fh.personOid.isDefined && fh.hetu.isDefined).slice(0,1))
      ) yield oppijat.headOption.fold(NotFound(body = ""))(Ok(_))
    }

  }

  incident {
    case t: VirtaConnectionErrorException => (id) => InternalServerError(IncidentReport(id, "virta error"))
  }


  def fetchOppijatFor(hakemukset: Seq[FullHakemus]): Future[Seq[Oppija]] =
    Future.sequence(for (
      hakemus <- hakemukset
      if hakemus.personOid.isDefined && hakemus.state.exists((state) => state == "ACTIVE")
    ) yield fetchOppijaData(hakemus.personOid.get, hakemus.hetu))


  def fetchTodistukset(suoritukset: Seq[Suoritus with Identified[UUID]]):Future[Seq[Todistus]] = Future.sequence(
    for (
      suoritus <- suoritukset
    ) yield for (
        arvosanat <- (rekisterit.arvosanaRekisteri ? ArvosanaQuery(suoritus = Some(suoritus.id))).mapTo[Seq[Arvosana]]
      ) yield Todistus(suoritus, arvosanat))

  def fetchOppijaData(henkiloOid: String, hetu: Option[String]): Future[Oppija] = {
    for (
      suoritukset <- fetchSuoritukset(henkiloOid);
      todistukset <- fetchTodistukset(suoritukset);
      opiskelu <- fetchOpiskelu(henkiloOid);
      opiskeluoikeudet <- fetchOpiskeluoikeudet(henkiloOid);
      ensikertalainen <- fetchEnsikertalaisuus(henkiloOid, hetu)
    ) yield Oppija(
      oppijanumero = henkiloOid,
      opiskelu = opiskelu,
      suoritukset = todistukset,
      opiskeluoikeudet = opiskeluoikeudet,
      ensikertalainen = ensikertalainen.map(_.ensikertalainen)
    )

  }


  def fetchEnsikertalaisuus(henkiloOid: String, hetu: Option[String]): Future[Option[Ensikertalainen]] = {
    (ensikertalaisuus ? EnsikertalainenQuery(henkiloOid, hetu)).mapTo[Ensikertalainen].
      map(Some(_)).
      recover{
        case NoHetuException(oid, message) =>
          logger.info(s"trying to resolve ensikertalaisuus for $henkiloOid, no hetu found")
          None
      }
  }

  def fetchOpiskeluoikeudet(henkiloOid: String): Future[Seq[Opiskeluoikeus]] = {
    (rekisterit.opiskeluoikeusRekisteri ? OpiskeluoikeusQuery(henkilo = Some(henkiloOid))).mapTo[Seq[Opiskeluoikeus]]
  }

  def fetchOpiskelu(henkiloOid: String): Future[Seq[Opiskelija]] = {
    (rekisterit.opiskelijaRekisteri ? OpiskelijaQuery(henkilo = Some(henkiloOid))).mapTo[Seq[Opiskelija]]
  }

  def fetchSuoritukset(henkiloOid: String): Future[Seq[Suoritus with Identified[UUID]]] = {
    (rekisterit.suoritusRekisteri ? SuoritusQuery(henkilo = Some(henkiloOid))).mapTo[Seq[Suoritus with Identified[UUID]]]
  }
}
