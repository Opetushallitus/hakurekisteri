package fi.vm.sade.hakurekisteri.web.oppija

import akka.actor.{ActorRef, ActorSystem}
import akka.event.{Logging, LoggingAdapter}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.integration.hakemus.HakemusQuery
import fi.vm.sade.hakurekisteri.integration.virta.VirtaConnectionErrorException
import fi.vm.sade.hakurekisteri.oppija.OppijaFetcher
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support._
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine}

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class OppijaResource(val rekisterit: Registers, val hakemusRekisteri: ActorRef, val ensikertalaisuus: ActorRef)
                    (implicit val system: ActorSystem, sw: Swagger, val security: Security)
    extends HakuJaValintarekisteriStack with OppijaFetcher with OppijaSwaggerApi with HakurekisteriJsonSupport
    with JacksonJsonSupport with FutureSupport with SecuritySupport with QueryLogging {

  override protected def applicationDescription: String = "Oppijan tietojen koosterajapinta"
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected implicit def executor: ExecutionContext = system.dispatcher
  implicit val defaultTimeout: Timeout = 500.seconds
  override val logger: LoggingAdapter = Logging.getLogger(system, this)
  def maxOppijatPostSize: Int = 5000

  before() {
    contentType = formats("json")
  }

  def getUser: User = {
    currentUser match {
      case Some(u) => u
      case None => throw UserNotAuthorized(s"anonymous access not allowed")
    }
  }

  def ensikertalaisuudenRajapvm(d: Option[String]): Option[DateTime] =
    d.flatMap(date => Try(ISODateTimeFormat.dateTimeParser.parseDateTime(date)).toOption)

  get("/", operation(query)) {
    val t0 = Platform.currentTime
    implicit val user = getUser
    val q = queryForParams(params)
    val rajapvm = ensikertalaisuudenRajapvm(params.get("ensikertalaisuudenRajapvm"))

    if (q.haku.isEmpty && q.hakukohde.isEmpty && q.organisaatio.isEmpty) {
      throw new IllegalArgumentException("at least one of parameters (haku, hakukohde, organisaatio) must be given")
    }

    new AsyncResult() {
      override implicit def timeout: Duration = 500.seconds

      private val oppijatFuture = fetchOppijat(q, rajapvm)

      logQuery(q, t0, oppijatFuture)

      val is = oppijatFuture
    }
  }

  import org.scalatra.util.RicherString._

  def queryForParams(params: Map[String,String]): HakemusQuery = HakemusQuery(
    haku = params.get("haku").flatMap(_.blankOption),
    organisaatio = params.get("organisaatio").flatMap(_.blankOption),
    None,
    hakukohde = params.get("hakukohde").flatMap(_.blankOption)
  )


  get("/:oid", operation(read)) {
    val t0 = Platform.currentTime
    implicit val user = getUser
    val personOid = params("oid")
    val rajapvm = ensikertalaisuudenRajapvm(params.get("ensikertalaisuudenRajapvm"))

    new AsyncResult() {
      override implicit def timeout: Duration = 500.seconds

      private val oppijaFuture =  fetchOppija(personOid, rajapvm)

      logQuery(personOid, t0, oppijaFuture)

      override val is = oppijaFuture
    }

  }

  post("/", operation(post)) {
    val t0 = Platform.currentTime
    implicit val user = getUser
    val henkilot = parse(request.body).extract[List[String]]
    if (henkilot.size > maxOppijatPostSize) throw new IllegalArgumentException("too many person oids")
    if (henkilot.exists(!_.startsWith("1.2.246.562.24."))) throw new IllegalArgumentException("person oid must start with 1.2.246.562.24.")
    val rajapvm = ensikertalaisuudenRajapvm(params.get("ensikertalaisuudenRajapvm"))

    new AsyncResult() {
      override implicit def timeout: Duration = 500.seconds

      private val oppijat = fetchOppijat(henkilot, hetuExists = true, rajapvm)

      logQuery(henkilot, t0, oppijat)

      override val is: Future[_] = oppijat
    }
  }

  incident {
    case t: VirtaConnectionErrorException => (id) => InternalServerError(IncidentReport(id, "virta error"))
    case t: IllegalArgumentException => (id) => BadRequest(IncidentReport(id, t.getMessage))
  }
}



