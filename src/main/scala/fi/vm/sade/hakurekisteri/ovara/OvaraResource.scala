package fi.vm.sade.hakurekisteri.ovara

import java.lang.Boolean.parseBoolean

import _root_.akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.auditlog.{Audit, Changes, Target}
import fi.vm.sade.hakurekisteri._
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, User}
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.hakija.HakijaQuery
import fi.vm.sade.hakurekisteri.web.kkhakija.{KkHakijaQuery, Query}
import fi.vm.sade.hakurekisteri.web.rest.support.ApiFormat.ApiFormat
import fi.vm.sade.hakurekisteri.web.rest.support._
import org.json4s._
import org.json4s.jackson.Serialization.write
import org.scalatra.{SessionSupport, _}
import org.scalatra.json.{JValueResult, JacksonJsonSupport}
import org.slf4j.LoggerFactory

import scala.util.Try

class OvaraResource(ovaraService: OvaraService)(implicit val security: Security)
    extends ScalatraServlet
    with JValueResult
    with JacksonJsonSupport
    with SessionSupport
    with SecuritySupport {
  val audit: Audit = SuoritusAuditVirkailija.audit

  private val logger = LoggerFactory.getLogger(classOf[OvaraResource])

  //Todo, require rekpit rights
  post("/muodosta") {
    val start = params.get("start").map(_.toLong)
    val end = params.get("end").map(_.toLong)
    logger.info(s"Muodostetaan siirtotiedosto! $start - $end")
    (start, end) match {
      case (Some(start), Some(end)) =>
        ovaraService.formSiirtotiedostotPaged(start, end)
        Ok("ok! :D")
      case _ =>
        BadRequest(s"Start ($start) ja end ($end) ovat pakollisia parametreja")
    }
  }

  override protected implicit def jsonFormats: Formats = HakurekisteriJsonSupport.format

}
