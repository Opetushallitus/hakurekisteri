package fi.vm.sade.hakurekisteri.arvosana

import akka.actor.{ActorSystem, ActorRef}
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.rest.support.{SpringSecuritySupport, HakurekisteriJsonSupport}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.{CorsSupport, FutureSupport}
import scala.concurrent.ExecutionContext


class SanityResource(sanityActor: ActorRef)(implicit system: ActorSystem) extends HakuJaValintarekisteriStack  with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with CorsSupport with SpringSecuritySupport{

  override protected implicit def executor: ExecutionContext = system.dispatcher

  import akka.pattern.ask

  before() {
    contentType = formats("json")
  }

  get("/perusopetus") {
    sanityActor ? Problems

  }

}
