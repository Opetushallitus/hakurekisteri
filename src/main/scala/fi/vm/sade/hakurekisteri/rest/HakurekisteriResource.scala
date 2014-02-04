package fi.vm.sade.hakurekisteri.rest

import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import org.scalatra.swagger.SwaggerSupport
import org.scalatra.json.{JacksonJsonSupport, JacksonJsonOutput}
import scala.concurrent.ExecutionContext
import akka.util.Timeout
import akka.actor.ActorSystem


abstract class HakurekisteriResource(system:ActorSystem) extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with SwaggerSupport {

  protected implicit def executor: ExecutionContext = system.dispatcher



  val timeout = 10

  implicit val defaultTimeout = Timeout(timeout)

  before() {
    contentType = formats("json")
  }


}
