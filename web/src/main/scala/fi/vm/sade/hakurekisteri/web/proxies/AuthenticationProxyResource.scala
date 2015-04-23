package fi.vm.sade.hakurekisteri.web.proxies

import _root_.akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.scalatra._
import org.scalatra.json.JsonSupport
import scala.concurrent.Future
import scala.io.Source


class AuthenticationProxyResource(config: Config, system: ActorSystem) extends OPHProxyServlet(system) with HakurekisteriJsonSupport {
  val proxy = config.mockMode match {
    case true => new MockAuthenticationProxy
    case false => new HttpAuthenticationProxy(config, system)
  }

  get("/buildversion.txt") {
    "artifactId=authentication-service\nmocked"
  }

  get("/resources/henkilo/:oid") {
    new AsyncResult() {
      val is = proxy.henkiloByOid(params("oid"))
    }
  }

  post("/resources/henkilo/henkilotByHenkiloOidList") {
    import org.json4s._
    import org.json4s.jackson.JsonMethods._
    new AsyncResult() {
      val parsedBody = parse(request.body)
      val is = proxy.henkilotByOidList(parsedBody.extract[List[String]])
    }
  }
}

trait AuthenticationProxy {
  def henkilotByOidList(oidList: List[String]): Future[String]
  def henkiloByOid(oid: String): Future[String]
}

class MockAuthenticationProxy extends AuthenticationProxy {
  lazy val data = Source.fromInputStream(getClass.getResourceAsStream("/proxy-mockdata/henkilot-by-oid-list.json")).mkString
  def henkilotByOidList(oidList: List[String]) = Future.successful(data)
  def henkiloByOid(oid: String) = Future.successful("""{"id":109341,"etunimet":"aa","syntymaaika":"1958-10-12","passinnumero":null,"hetu":"123456-789","kutsumanimi":"aa","oidHenkilo":"1.2.246.562.24.71944845619","oppijanumero":null,"sukunimi":"AA","sukupuoli":"1","turvakielto":null,"henkiloTyyppi":"OPPIJA","eiSuomalaistaHetua":false,"passivoitu":false,"yksiloity":false,"yksiloityVTJ":true,"yksilointiYritetty":false,"duplicate":false,"created":null,"modified":null,"kasittelijaOid":null,"asiointiKieli":null,"aidinkieli":null,"huoltaja":null,"kayttajatiedot":null,"kielisyys":[],"kansalaisuus":[],"yhteystiedotRyhma":[]}""")
}

class HttpAuthenticationProxy(config: Config, system: ActorSystem) extends OphProxy(config, system, config.integrations.henkiloConfig, "authentication-proxy") with AuthenticationProxy {
  override def henkilotByOidList(oidList: List[String]) = {
    client.postObject[List[String], String]("/resources/henkilo/henkilotByHenkiloOidList", 200, oidList)
  }
  def henkiloByOid(oid: String) = {
    client.readObject[String]("/resources/henkilo/" + oid, 200, 1)
  }
}