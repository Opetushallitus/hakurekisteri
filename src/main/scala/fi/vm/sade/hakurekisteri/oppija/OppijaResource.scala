package fi.vm.sade.hakurekisteri.oppija

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, ArvosanaQuery}
import fi.vm.sade.hakurekisteri.ensikertalainen.{Ensikertalainen, EnsikertalainenQuery, NoHetuException}
import fi.vm.sade.hakurekisteri.integration.hakemus.{FullHakemus, HakemusQuery, HenkiloHakijaQuery}
import fi.vm.sade.hakurekisteri.integration.virta.VirtaConnectionErrorException
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaQuery}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusQuery}
import fi.vm.sade.hakurekisteri.rest.support.{User, HakurekisteriJsonSupport, Registers, SpringSecuritySupport}
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusQuery}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.Swagger

import scala.concurrent.{ExecutionContext, Future}
import fi.vm.sade.hakurekisteri.organization.AuthorizedQuery


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
    implicit val user = currentUser
    val q = HakemusQuery(params)
    new AsyncResult() {
      override implicit def timeout: Duration = 500.seconds
      val is = for (
        hakemukset <- (hakemusRekisteri ? q).mapTo[Seq[FullHakemus]];
        oppijat <- fetchOppijatFor(hakemukset)
      ) yield oppijat
    }
  }

  get("/:oid") {
    implicit val user = currentUser
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

  def fetchOppijatFor(hakemukset: Seq[FullHakemus])(implicit user: Option[User]): Future[Seq[Oppija]] =
    Future.sequence(for (
      hakemus <- hakemukset
      if hakemus.personOid.isDefined && hakemus.stateValid
    ) yield fetchOppijaData(hakemus.personOid.get, hakemus.hetu))


  def fetchTodistukset(suoritukset: Seq[Suoritus with Identified[UUID]])(implicit user: Option[User]):Future[Seq[Todistus]] = Future.sequence(
    for (
      suoritus <- suoritukset
    ) yield for (
        arvosanat <- (rekisterit.arvosanaRekisteri ? AuthorizedQuery(ArvosanaQuery(suoritus = Some(suoritus.id)), authorities, username)).mapTo[Seq[Arvosana]]
      ) yield Todistus(suoritus, arvosanat))

  def fetchOppijaData(henkiloOid: String, hetu: Option[String])(implicit user: Option[User]): Future[Oppija] = {
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

  def fetchOpiskeluoikeudet(henkiloOid: String)(implicit user: Option[User]): Future[Seq[Opiskeluoikeus]] = {
    (rekisterit.opiskeluoikeusRekisteri ? AuthorizedQuery(OpiskeluoikeusQuery(henkilo = Some(henkiloOid)), authorities, username)).mapTo[Seq[Opiskeluoikeus]]
  }

  def fetchOpiskelu(henkiloOid: String)(implicit user: Option[User]): Future[Seq[Opiskelija]] = {
    (rekisterit.opiskelijaRekisteri ? AuthorizedQuery(OpiskelijaQuery(henkilo = Some(henkiloOid)), authorities, username)).mapTo[Seq[Opiskelija]]
  }

  def fetchSuoritukset(henkiloOid: String)(implicit user: Option[User]): Future[Seq[Suoritus with Identified[UUID]]] = {
    (rekisterit.suoritusRekisteri ? AuthorizedQuery(SuoritusQuery(henkilo = Some(henkiloOid)), authorities, username)).mapTo[Seq[Suoritus with Identified[UUID]]]
  }

  def authorities(implicit user:Option[User]) = getKnownOrganizations(user)

  def username(implicit user:Option[User]) = user.map(_.username).getOrElse("anonymous")

}
