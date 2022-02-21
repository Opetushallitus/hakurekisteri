package fi.vm.sade.hakurekisteri.web.valpas

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.hakurekisteri.integration.valpas.{ValpasHakemus, ValpasIntergration, ValpasQuery}
import fi.vm.sade.hakurekisteri.rest.support.ValpasReadRole
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.{Security, SecuritySupport, UserNotAuthorized}
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.{AsyncResult, FutureSupport, InternalServerError}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine, SwaggerSupport, SwaggerSupportSyntax}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Success, Try}

trait ValpasSwaggerApi extends SwaggerSupport {

  val fetchValpasDataForPersons: SwaggerSupportSyntax.OperationBuilder =
    apiOperation[Seq[ValpasHakemus]]("fetchValpasDataForPersons")
      .summary("Hakijoille Valpas-tiedot")
      .description(
        "Palauttaa hakijoiden oppijanumeroille Valpas-tiedot. Rajapintaa voi kutsua maksimissaan 3000 oppijanumerolla kerallaan."
      )
      .parameter(
        bodyParam[Seq[String]]("hakijaOids").description("hakijoiden oppijanumerot").required
      )
      .parameter(
        queryParam[Option[Boolean]]("ainoastaanAktiivisetHaut")
          .description(
            "Palautetaanko ainoastaan aktiiviset haut. Palauttaa oletusarvoisesti kaikki haut."
          )
          .optional
      )
      .tags("Valpas-resource")

}

class ValpasServlet(valpasIntergration: ValpasIntergration)(implicit
  val sw: Swagger,
  val system: ActorSystem,
  val security: Security
) extends HakuJaValintarekisteriStack
    with JacksonJsonSupport
    with SecuritySupport
    with ValpasSwaggerApi
    with FutureSupport {
  override val logger: LoggingAdapter = Logging.getLogger(system, this)
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected def applicationDescription: String = "Valpas-Resource"
  override protected implicit def jsonFormats: Formats = DefaultFormats
  override protected implicit def executor: ExecutionContext = system.dispatcher

  def shouldBeAdminOrValpasRead(): Unit =
    if (!currentUser.exists(user => user.isAdmin || user.hasRole(ValpasReadRole)))
      throw UserNotAuthorized("not authorized")

  before() {
    contentType = formats("json")
  }

  post("/", operation(fetchValpasDataForPersons)) {
    shouldBeAdminOrValpasRead()

    val ainoastaanAktiivisetHaut: Boolean =
      Try(Option(params("ainoastaanAktiivisetHaut")).map(_.toBoolean)) match {
        case Success(Some(aktiiviset)) => aktiiviset
        case _                         => false
      }
    if (ainoastaanAktiivisetHaut) {
      logger.debug("Palautetaan ainoastaan aktiiviset haut!")
    }

    val personOids = parse(request.body).extract[Set[String]]
    val tooManyPersonOidsAtOnce: Boolean = personOids.size > 3000
    val f: Future[Any] =
      if (tooManyPersonOidsAtOnce)
        Future.successful(
          InternalServerError(body =
            Map(
              "reason" -> s"Maximum of 3000 person OIDs allowed. API was called with ${personOids.size} person OIDs."
            )
          )
        )
      else
        valpasIntergration.fetch(ValpasQuery(personOids, ainoastaanAktiivisetHaut)).recoverWith {
          case t: Throwable =>
            logger.error(s"Valpas fetch failed: ${t.getMessage}")
            Future.successful(InternalServerError(body = Map("reason" -> t.getMessage)))
        }

    new AsyncResult() {
      override implicit def timeout: Duration = 360.seconds

      override val is: Future[Any] = f
    }
  }

}
