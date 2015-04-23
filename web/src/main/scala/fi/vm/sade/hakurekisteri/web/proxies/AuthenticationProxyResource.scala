package fi.vm.sade.hakurekisteri.web.proxies

import org.scalatra.ScalatraServlet

class AuthenticationProxyResource extends ScalatraServlet {
  get("/buildversion.txt") {
    "artifactId=authentication-service\nmocked"
  }
}
