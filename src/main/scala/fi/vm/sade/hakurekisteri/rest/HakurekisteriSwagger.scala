package fi.vm.sade.hakurekisteri.rest

import org.scalatra.swagger.{JacksonSwaggerBase, NativeSwaggerBase, Swagger}

import org.scalatra.ScalatraServlet
import org.json4s.{DefaultFormats, Formats}



class ResourcesApp(implicit val swagger: Swagger) extends ScalatraServlet with JacksonSwaggerBase {
  implicit override val jsonFormats: Formats = DefaultFormats
}

class HakurekisteriSwagger extends Swagger("1.0", "1")