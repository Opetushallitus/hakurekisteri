package fi.vm.sade.hakurekisteri.web.ensikertalainen

import java.util.concurrent.ExecutionException

import akka.actor.{ActorRef, ActorSystem}
import akka.event.{Logging, LoggingAdapter}
import akka.pattern.ask
import fi.vm.sade.hakurekisteri.ensikertalainen.{Ensikertalainen, EnsikertalainenQuery, HetuNotFoundException}
import fi.vm.sade.hakurekisteri.integration.PreconditionFailedException
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.EnsimmainenVastaanotto
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.{IncidentReport, Security, SecuritySupport}
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Try

case class ParamMissingException(message: String) extends IllegalArgumentException(message)

class EnsikertalainenResource(ensikertalainenActor: ActorRef)
                             (implicit val sw: Swagger, system: ActorSystem, val security: Security) extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with EnsikertalainenSwaggerApi with JacksonJsonSupport with FutureSupport with SecuritySupport {

  override protected def applicationDescription: String = "Korkeakouluhakujen kiintiöiden ensikertalaisuuden kyselyrajapinta"
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected implicit def executor: ExecutionContext = system.dispatcher
  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  before() {
    contentType = formats("json")
  }

  def ensikertalaisuudenRajapvm(d: Option[String]): Option[DateTime] = d.flatMap(date => Try(ISODateTimeFormat.dateTimeParser.parseDateTime(date)).toOption)

  get("/", operation(query)) {
    try {
      val henkiloOid = params("henkilo")
      val rajapvm = ensikertalaisuudenRajapvm(params.get("ensikertalaisuudenRajapvm"))

      new AsyncResult() {
        override implicit def timeout: Duration = 60.seconds
        override val is = (ensikertalainenActor ? EnsikertalainenQuery(Set(henkiloOid), paivamaara = rajapvm))(60.seconds).mapTo[Seq[Ensikertalainen]].map(_.head)
      }
    } catch {
      case t: NoSuchElementException => throw ParamMissingException("parameter henkilo missing")
    }
  }

  incident {
    case t: ParamMissingException => (id) => BadRequest(IncidentReport(id, t.getMessage))
    case t: HetuNotFoundException => (id) => BadRequest(IncidentReport(id, "henkilo does not have hetu; add hetu and try again"))
    case t: ExecutionException => (id) => InternalServerError(IncidentReport(id, "backend service failed"))
    case t: PreconditionFailedException => (id) => InternalServerError(IncidentReport(id, "backend service failed"))
  }

}

