package fi.vm.sade.hakurekisteri.web.rekisteritiedot

import fi.vm.sade.hakurekisteri.rest.support.{User, SpringSecuritySupport, HakurekisteriJsonSupport, Registers}
import _root_.akka.actor.{ActorRef, ActorSystem}
import org.scalatra.swagger.{SwaggerEngine, Swagger}
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.oppija.{Todistus, Oppija, OppijaFetcher}
import fi.vm.sade.hakurekisteri.web.oppija.OppijaSwaggerApi
import org.scalatra.json.JacksonJsonSupport
import fi.vm.sade.hakurekisteri.web.rest.support._
import scala.concurrent.{Future, ExecutionContext}
import _root_.akka.util.Timeout
import _root_.akka.event.{Logging, LoggingAdapter}
import scala.compat.Platform
import scala.concurrent.duration._
import fi.vm.sade.hakurekisteri.integration.hakemus.HakemusQuery
import scala.Some
import fi.vm.sade.hakurekisteri.integration.hakemus.HenkiloHakijaQuery
import fi.vm.sade.hakurekisteri.integration.hakemus.FullHakemus
import fi.vm.sade.hakurekisteri.integration.virta.VirtaConnectionErrorException
import fi.vm.sade.hakurekisteri.web.rest.support.UserNotAuthorized
import fi.vm.sade.hakurekisteri.suoritus.{SuoritusQuery, Suoritus}
import fi.vm.sade.hakurekisteri.storage.Identified
import java.util.UUID
import fi.vm.sade.hakurekisteri.organization.AuthorizedQuery
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, ArvosanaQuery}
import fi.vm.sade.hakurekisteri.ensikertalainen.{EnsikertalainenQuery, Ensikertalainen}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{OpiskeluoikeusQuery, Opiskeluoikeus}
import fi.vm.sade.hakurekisteri.opiskelija.{OpiskelijaQuery, Opiskelija}
import org.scalatra.{InternalServerError, CorsSupport, FutureSupport, AsyncResult}

class RekisteritiedotResource(val rekisterit: Registers)
                    (implicit val system: ActorSystem, sw: Swagger)
  extends HakuJaValintarekisteriStack with TiedotFetcher with OppijaSwaggerApi with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with CorsSupport with SpringSecuritySupport with QueryLogging {

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
    val q = queryForParams(params)

    new AsyncResult() {
      override implicit def timeout: Duration = 500.seconds

      private val tiedotFuture = fetchTiedot(q)

      logQuery(q, t0, tiedotFuture)

      val is = tiedotFuture
    }
  }



  import org.scalatra.util.RicherString._

  def queryForParams(params: Map[String,String]): RekisteriQuery = RekisteriQuery(
    oppilaitosOid = params.get("oppilaitosOid").flatMap(_.blankOption),
    vuosi = params.get("vuosi").flatMap(_.blankOption)
  )


  get("/:oid", operation(read)) {
    val t0 = Platform.currentTime
    implicit val user = getUser
    val q = HenkiloHakijaQuery(params("oid"))

    new AsyncResult() {
      override implicit def timeout: Duration = 500.seconds

      private val tiedotFuture = fetchTiedot(params("oid"))
      logQuery(q, t0, tiedotFuture)

      val is = tiedotFuture
    }

  }

  incident {
    case t: VirtaConnectionErrorException => (id) => InternalServerError(IncidentReport(id, "virta error"))
  }
}

trait TiedotFetcher {

  val rekisterit: Registers

  protected implicit def executor: ExecutionContext
  implicit val defaultTimeout: Timeout

  def fetchTodistuksetFor(query: RekisteriQuery)(implicit user: User):Future[Seq[Todistus]] = fetchSuoritukset(query).flatMap(fetchTodistukset)



  def fetchTiedot(q: RekisteriQuery)(implicit user: User): Future[Seq[Oppija]] = {
    for (
      todistukset <- fetchTodistuksetFor(q);
      opiskelijat <- fetchOpiskelu(q);
      crossed <- crossCheck(opiskelijat, todistukset)
    ) yield crossed.toSeq
  }

  def fetchTiedot(oid: String)(implicit user: User): Future[Oppija] = {
    for (
      todistukset <- fetchTodistukset(oid);
      opiskeluhistoria <- fetchOpiskelu(oid);
      opintoOikeudet <- fetchOpiskeluoikeudet(oid)
    ) yield Oppija(oid, opiskeluhistoria, todistukset, opintoOikeudet, None)
  }


  def crossCheck(opiskelijat: Seq[Opiskelija], todistukset: Seq[Todistus])(implicit user: User): Future[Set[Oppija]] = {
    val opiskelijaTiedot = opiskelijat.groupBy(_.henkiloOid)
    val todistusTiedot = todistukset.groupBy(_.suoritus.henkiloOid)
    val found = opiskelijaTiedot.keySet.union(todistusTiedot.keySet)
    val all = for (
      henkilo <- found
    ) yield (opiskelijaTiedot.get(henkilo), todistusTiedot.get(henkilo)) match {
        case (Some(oppilaitoshistoria), Some(todistukset)) =>
          Future.successful(Oppija(henkilo, oppilaitoshistoria, todistukset, Seq(), None))
        case (None, Some(todistukset)) => fetchOpiskelu(henkilo).map(Oppija(henkilo, _, todistukset, Seq(), None))
        case (Some(oppilaitoshistoria), None) => fetchTodistukset(henkilo).map(Oppija(henkilo, oppilaitoshistoria, _, Seq(), None))
        case (None, None) => throw new RuntimeException("Somehow maps were mutated")
      }
    Future.sequence(all)
  }


  def fetchTodistukset(henkilo: String)(implicit user: User): Future[Seq[Todistus]] = for (
    suoritukset <- fetchSuoritukset(henkilo);
    todistukset <- fetchTodistukset(suoritukset)
  ) yield todistukset

  import akka.pattern.ask

  def fetchTodistukset(suoritukset: Seq[Suoritus with Identified[UUID]])(implicit user: User):Future[Seq[Todistus]] = Future.sequence(
    for (
      suoritus <- suoritukset
    ) yield for (
      arvosanat <- (rekisterit.arvosanaRekisteri ? AuthorizedQuery(ArvosanaQuery(suoritus = Some(suoritus.id)), user)).mapTo[Seq[Arvosana]]
    ) yield Todistus(suoritus, arvosanat))




  def fetchOpiskeluoikeudet(henkiloOid: String)(implicit user: User): Future[Seq[Opiskeluoikeus]] = {
    (rekisterit.opiskeluoikeusRekisteri ? AuthorizedQuery(OpiskeluoikeusQuery(henkilo = Some(henkiloOid)), user)).mapTo[Seq[Opiskeluoikeus]]
  }

  def fetchOpiskelu(henkiloOid: String)(implicit user: User): Future[Seq[Opiskelija]] = {
    (rekisterit.opiskelijaRekisteri ? AuthorizedQuery(OpiskelijaQuery(henkilo = Some(henkiloOid)), user)).mapTo[Seq[Opiskelija]]
  }

  def fetchOpiskelu(q: RekisteriQuery)(implicit user: User): Future[Seq[Opiskelija]] = {
    (rekisterit.opiskelijaRekisteri ? AuthorizedQuery(OpiskelijaQuery(oppilaitosOid = q.oppilaitosOid, vuosi = q.vuosi), user)).mapTo[Seq[Opiskelija]]
  }

  def fetchSuoritukset(henkiloOid: String)(implicit user: User): Future[Seq[Suoritus with Identified[UUID]]] = {
    (rekisterit.suoritusRekisteri ? AuthorizedQuery(SuoritusQuery(henkilo = Some(henkiloOid)), user)).mapTo[Seq[Suoritus with Identified[UUID]]]
  }

  def fetchSuoritukset(q: RekisteriQuery)(implicit user: User): Future[Seq[Suoritus with Identified[UUID]]] = {
    (rekisterit.suoritusRekisteri ? AuthorizedQuery(SuoritusQuery(myontaja = q.oppilaitosOid, vuosi = q.vuosi), user)).mapTo[Seq[Suoritus with Identified[UUID]]]
  }

}

case class RekisteriQuery(oppilaitosOid: Option[String], vuosi: Option[String])
