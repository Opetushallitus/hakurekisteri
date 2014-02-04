package fi.vm.sade.hakurekisteri.rest

import org.scalatra.swagger.{JacksonSwaggerBase, NativeSwaggerBase, Swagger}

import org.scalatra.ScalatraServlet
import org.json4s.{DefaultFormats, Formats}



class ResourcesApp(implicit val swagger: Swagger) extends ScalatraServlet with HakurekisteriJsonSupport with JacksonSwaggerBase  {
  override implicit val jsonFormats = super[HakurekisteriJsonSupport].jsonFormats
}

class HakurekisteriSwagger extends Swagger("1.0", "1")