package fi.vm.sade.hakurekisteri.web.proxies

import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.{ServiceConfig, VirkailijaRestClient}
import scala.concurrent.ExecutionContext

class OphProxy(config: Config, system: ActorSystem, serviceConfig: ServiceConfig, name: String) {
  implicit val ec: ExecutionContext = system.dispatcher
  // TODO: not so nice to duplicate the rest client here
  val client = new VirkailijaRestClient(serviceConfig, None)(ec, system) {
    override def serviceName = Some(name)
  }
}
