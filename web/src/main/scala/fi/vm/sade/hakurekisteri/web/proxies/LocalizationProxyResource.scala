package fi.vm.sade.hakurekisteri.web.proxies

import org.scalatra.ScalatraServlet

class LocalizationProxyResource extends ScalatraServlet {
  get("/cxf/rest/v1/localisation") {
    getClass.getResourceAsStream("/proxy-mockdata/localization.json")
  }
}
