package fi.vm.sade.hakurekisteri.web.oppija

import akka.actor.{ActorRef, ActorSystem}
import akka.event.{Logging, LoggingAdapter}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.integration.hakemus.{HakemusQuery, IHakemusService}
import fi.vm.sade.hakurekisteri.integration.haku.HakuNotFoundException
import fi.vm.sade.hakurekisteri.integration.henkilo.IOppijaNumeroRekisteri
import fi.vm.sade.hakurekisteri.oppija.OppijaFetcher
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support._
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine}

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object OppijatPostSize {
  def maxOppijatPostSize: Int = 5000
}

class OppijaResource(val rekisterit: Registers, val hakemusService: IHakemusService, val ensikertalaisuus: ActorRef, val oppijaNumeroRekisteri: IOppijaNumeroRekisteri)
                    (implicit val system: ActorSystem, sw: Swagger, val security: Security)
    extends HakuJaValintarekisteriStack with OppijaFetcher with OppijaSwaggerApi with HakurekisteriJsonSupport
    with JacksonJsonSupport with FutureSupport with SecuritySupport with QueryLogging {

  override protected def applicationDescription: String = "Oppijan tietojen koosterajapinta"
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected implicit def executor: ExecutionContext = system.dispatcher
  implicit val defaultTimeout: Timeout = 500.seconds
  override val logger: LoggingAdapter = Logging.getLogger(system, this)

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
    import org.scalatra.util.RicherString._
    val t0 = Platform.currentTime
    implicit val user = getUser
    val ensikertalaisuudet = params.getOrElse("ensikertalaisuudet", "true").toBoolean
    val q = HakemusQuery(
      haku = if (ensikertalaisuudet) Some(params("haku")) else params.get("haku"),
      organisaatio = params.get("organisaatio").flatMap(_.blankOption),
      None,
      hakukohde = params.get("hakukohde").flatMap(_.blankOption)
    )

    new AsyncResult() {
      override implicit def timeout: Duration = 500.seconds

      private val oppijatFuture = fetchOppijat(ensikertalaisuudet, q)

      logQuery(q, t0, oppijatFuture)

      val is = oppijatFuture
    }
  }

  get("/:oid", operation(read)) {
    val t0 = Platform.currentTime
    implicit val user = getUser
    val personOid = params("oid")
    val hakuOid = params.get("haku")

    new AsyncResult() {
      override implicit def timeout: Duration = 500.seconds

      private val oppijaFuture = fetchOppija(personOid, hakuOid.isDefined, hakuOid)

      logQuery(Map("oid" -> personOid, "haku" -> hakuOid), t0, oppijaFuture)

      override val is = oppijaFuture
    }

  }

  post("/", operation(post)) {
    val t0 = Platform.currentTime
    implicit val user = getUser
    val ensikertalaisuudet = params.getOrElse("ensikertalaisuudet", "true").toBoolean
    val henkilot = parse(request.body).extract[Set[String]]
    if (henkilot.size > OppijatPostSize.maxOppijatPostSize) throw new IllegalArgumentException("too many person oids")
    if (henkilot.exists(!_.startsWith("1.2.246.562.24."))) throw new IllegalArgumentException("person oid must start with 1.2.246.562.24.")
    val hakuOid = params("haku")

    new AsyncResult() {
      override implicit def timeout: Duration = 500.seconds

      private val oppijat = fetchOppijat(henkilot, ensikertalaisuudet, HakemusQuery(haku = Some(hakuOid)))

      logQuery(Map("henkilot" -> henkilot, "haku" -> hakuOid), t0, oppijat)

      override val is: Future[_] = oppijat
    }
  }

  incident {
    case t: HakuNotFoundException => (id) => NotFound(IncidentReport(id, t.getMessage))
    case t: NoSuchElementException => (id) => BadRequest(IncidentReport(id, t.getMessage))
    case t: IllegalArgumentException => (id) => BadRequest(IncidentReport(id, t.getMessage))
  }
}



