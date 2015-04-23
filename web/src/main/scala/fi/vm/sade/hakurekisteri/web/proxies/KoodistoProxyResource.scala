package fi.vm.sade.hakurekisteri.web.proxies

import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.json4s.JValue

class KoodistoProxyResource(config: Config, system: ActorSystem) extends OPHProxyServlet(system) with HakurekisteriJsonSupport {
  val proxy = config.mockMode match {
    case true => new MockKoodistoProxy
    case false => new HttpKoodistoProxy(config, system)
  }

  get("/rest/json/:id/koodi*") {
    proxy.koodi(params("id"))
  }
}

trait KoodistoProxy {
  def koodi(id: String)
}

class MockKoodistoProxy extends KoodistoProxy with HakurekisteriJsonSupport {
  import org.json4s._
  import org.json4s.jackson.JsonMethods._

  lazy val koodisto: Map[String, JValue] = Extraction.extract[Map[String, JValue]](parse(getClass.getResourceAsStream("/proxy-mockdata/koodisto.json")))

  def koodi(id: String) = koodisto(id)
}

class HttpKoodistoProxy(config: Config, system: ActorSystem) extends OphProxy(config, system, config.integrations.koodistoConfig, "koodisto-proxy") with KoodistoProxy {
  def koodi(id: String) = {
    client.readObject[JValue]("/rest/json/" + id + "/koodi", 200, 1)
  }
}