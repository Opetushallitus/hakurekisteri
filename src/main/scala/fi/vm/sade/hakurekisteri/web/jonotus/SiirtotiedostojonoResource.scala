package fi.vm.sade.hakurekisteri.web.jonotus

import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.rest.support.{Security, SecuritySupport}
import org.scalatra.SessionSupport
import org.scalatra._
import org.scalatra.atmosphere._
import org.slf4j.LoggerFactory
import scalate.ScalateSupport
import org.scalatra.json.{JValueResult, JacksonJsonSupport}
import scala.concurrent._
import ExecutionContext.Implicits.global
import org.json4s.jackson.Serialization.{write}
import org.json4s._

case class Sijoitus(sijoitus: Int)

class SiirtotiedostojonoResource(implicit val security: Security) extends ScalatraServlet
  with ScalateSupport with JValueResult
  with JacksonJsonSupport with SessionSupport
  with AtmosphereSupport with SecuritySupport {

  private val logger = LoggerFactory.getLogger(classOf[SiirtotiedostojonoResource])

  atmosphere("/") {
    new AtmosphereClient {
      def receive = {
        case Connected =>
          logger.info(s"User connected $currentUser!")
        case Disconnected(disconnector, couldContainErrors) =>
          couldContainErrors.foreach(logger.error("Disconnected with exception!",_))
        case JsonMessage(json) => send(write(Sijoitus(54)))
          logger.info(s"User $currentUser sent message $json")
        case event =>
          logger.error(s"Unexpected event in 'siirtotiedoston jonotus' $event")
      }
    }
  }

  override protected implicit def jsonFormats: Formats = HakurekisteriJsonSupport.format

}
