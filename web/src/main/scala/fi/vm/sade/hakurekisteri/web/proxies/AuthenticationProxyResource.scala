package fi.vm.sade.hakurekisteri.web.proxies

import org.scalatra.ScalatraServlet

class AuthenticationProxyResource extends ScalatraServlet {
  get("/buildversion.txt") {
    "artifactId=authentication-service\nmocked"
  }

  before() {
    contentType = "application/json"
  }

  post("/resources/henkilo/henkilotByHenkiloOidList") {
    getClass.getResourceAsStream("/proxy-mockdata/henkilot-by-oid-list.json")
  }
}
