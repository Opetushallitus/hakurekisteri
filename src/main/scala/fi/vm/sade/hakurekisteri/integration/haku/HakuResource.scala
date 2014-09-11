package fi.vm.sade.hakurekisteri.integration.haku

import akka.actor.{ActorSystem, ActorRef}
import org.scalatra.swagger.Swagger
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.rest.support.{SpringSecuritySupport, HakurekisteriJsonSupport}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.{AsyncResult, CorsSupport, FutureSupport}
import scala.concurrent.ExecutionContext
import akka.util.Timeout


class HakuResource(hakijaActor: ActorRef)(implicit system: ActorSystem, sw: Swagger) extends HakuJaValintarekisteriStack  with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with CorsSupport with SpringSecuritySupport  {

  override protected implicit def executor: ExecutionContext = system.dispatcher

  options("/*") {
    response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
  }

  before() {
    contentType = formats("json")
  }

  get("/") {

    new AsyncResult() {
      import scala.concurrent.duration._
      import akka.pattern.ask
      override implicit def timeout: Duration = 300.seconds
      implicit val defaultTimeout: Timeout = 299.seconds
      val is = hakijaActor ? HakuRequest
    }
  }

}

