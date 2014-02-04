package fi.vm.sade.hakurekisteri.rest.support

import org.scalatra.swagger.{JacksonSwaggerBase, Swagger}

import org.scalatra.ScalatraServlet


class ResourcesApp(implicit val swagger: Swagger) extends ScalatraServlet with HakurekisteriJsonSupport with JacksonSwaggerBase  {
  override implicit val jsonFormats = super[HakurekisteriJsonSupport].jsonFormats
}

class HakurekisteriSwagger extends Swagger("1.0", "1")