package fi.vm.sade.hakurekisteri.oppija

import java.util.UUID

import _root_.akka.event.{LoggingAdapter, Logging}
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, ArvosanaQuery}
import fi.vm.sade.hakurekisteri.ensikertalainen.{Ensikertalainen, EnsikertalainenQuery}
import fi.vm.sade.hakurekisteri.integration.hakemus.{FullHakemus, HakemusQuery, HenkiloHakijaQuery}
import fi.vm.sade.hakurekisteri.integration.virta.{VirtaValidationError, VirtaConnectionErrorException}
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaQuery}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusQuery}
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusQuery}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{SwaggerEngine, Swagger}

import scala.compat.Platform
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import fi.vm.sade.hakurekisteri.organization.AuthorizedQuery


class OppijaResource(val rekisterit: Registers, val hakemusRekisteri: ActorRef, val ensikertalaisuus: ActorRef)
                    (implicit val system: ActorSystem, sw: Swagger)
    extends HakuJaValintarekisteriStack with OppijaFetcher with OppijaSwaggerApi with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with CorsSupport with SpringSecuritySupport with QueryLogging {

  override protected def applicationDescription: String = "Oppijan tietojen koosterajapinta"
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected implicit def executor: ExecutionContext = system.dispatcher
  implicit val defaultTimeout: Timeout = 500.seconds
  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  options("/*") {
    response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
  }

  before() {
    contentType = formats("json")
  }

  def getUser: User = {
    currentUser match {
      case Some(u) => u
      case None => throw UserNotAuthorized(s"anonymous access not allowed")
    }
  }

  get("/", operation(query)) {
    val t0 = Platform.currentTime
    implicit val user = getUser
    val q = HakemusQuery(params)

    new AsyncResult() {
      override implicit def timeout: Duration = 500.seconds

      private val oppijatFuture = fetchOppijat(q)

      logQuery(q, t0, oppijatFuture)

      val is = oppijatFuture
    }
  }

  get("/:oid", operation(read)) {
    val t0 = Platform.currentTime
    implicit val user = getUser
    val q = HenkiloHakijaQuery(params("oid"))

    new AsyncResult() {
      override implicit def timeout: Duration = 500.seconds

      private val oppijaFuture = for (
        hakemukset <- (hakemusRekisteri ? q).mapTo[Seq[FullHakemus]];
        oppijat <- fetchOppijatFor(hakemukset.filter((fh) => fh.personOid.isDefined && fh.hetu.isDefined).slice(0, 1))
      ) yield {
        oppijat.headOption.fold(NotFound(body = ""))(Ok(_))
      }

      logQuery(q, t0, oppijaFuture)

      val is = oppijaFuture
    }

  }

  incident {
    case t: VirtaConnectionErrorException => (id) => InternalServerError(IncidentReport(id, "virta error"))
  }
}

trait OppijaFetcher { this: HakuJaValintarekisteriStack =>

  val rekisterit: Registers
  val hakemusRekisteri: ActorRef
  val ensikertalaisuus: ActorRef

  protected implicit def executor: ExecutionContext
  implicit val defaultTimeout: Timeout

  def fetchOppijat(q: HakemusQuery)(implicit user: User): Future[Seq[Oppija]] = {
    for (
      hakemukset <- (hakemusRekisteri ? q).mapTo[Seq[FullHakemus]];
      oppijat <- fetchOppijatFor(hakemukset)
    ) yield oppijat
  }

  def fetchOppijatFor(hakemukset: Seq[FullHakemus])(implicit user: User): Future[Seq[Oppija]] =
    Future.sequence(for (
      hakemus <- hakemukset
      if hakemus.personOid.isDefined && hakemus.stateValid
    ) yield fetchOppijaData(hakemus.personOid.get, hakemus.hetu))


  def fetchTodistukset(suoritukset: Seq[Suoritus with Identified[UUID]])(implicit user: User):Future[Seq[Todistus]] = Future.sequence(
    for (
      suoritus <- suoritukset
    ) yield for (
      arvosanat <- (rekisterit.arvosanaRekisteri ? AuthorizedQuery(ArvosanaQuery(suoritus = Some(suoritus.id)), user)).mapTo[Seq[Arvosana]]
    ) yield Todistus(suoritus, arvosanat))

  def fetchOppijaData(henkiloOid: String, hetu: Option[String])(implicit user: User): Future[Oppija] = {
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

  def fetchEnsikertalaisuus(henkiloOid: String, hetu: Option[String]): Future[Option[Ensikertalainen]] = hetu match {
    case Some(_) => (ensikertalaisuus ? EnsikertalainenQuery(henkiloOid)).mapTo[Ensikertalainen].map(Some(_))
    case None => Future.successful(None)
  }

  def fetchOpiskeluoikeudet(henkiloOid: String)(implicit user: User): Future[Seq[Opiskeluoikeus]] = {
    (rekisterit.opiskeluoikeusRekisteri ? AuthorizedQuery(OpiskeluoikeusQuery(henkilo = Some(henkiloOid)), user)).mapTo[Seq[Opiskeluoikeus]]
  }

  def fetchOpiskelu(henkiloOid: String)(implicit user: User): Future[Seq[Opiskelija]] = {
    (rekisterit.opiskelijaRekisteri ? AuthorizedQuery(OpiskelijaQuery(henkilo = Some(henkiloOid)), user)).mapTo[Seq[Opiskelija]]
  }

  def fetchSuoritukset(henkiloOid: String)(implicit user: User): Future[Seq[Suoritus with Identified[UUID]]] = {
    (rekisterit.suoritusRekisteri ? AuthorizedQuery(SuoritusQuery(henkilo = Some(henkiloOid)), user)).mapTo[Seq[Suoritus with Identified[UUID]]]
  }
}
