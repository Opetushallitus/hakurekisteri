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
import fi.vm.sade.utils.slf4j.Logging
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
    with SecuritySupport
    with Logging {
  val audit: Audit = SuoritusAuditVirkailija.audit

  //def shouldBeAdmin = if (!currentUser.exists(_.isAdmin)) throw UserNotAuthorized("not authorized")

  //Todo, require rekpit rights
  get("/muodosta") {
    val start = params.get("start").map(_.toLong)
    val end = params.get("end").map(_.toLong)
    (start, end) match {
      case (Some(start), Some(end)) =>
        logger.info(s"Muodostetaan siirtotiedosto! $start - $end")
        val result = ovaraService.formSiirtotiedostotPaged(start, end)
        Ok(s"$result")
      case _ =>
        logger.error(s"Toinen pakollisista parametreista (start $start, end $end) puuttuu!")
        BadRequest(s"Start ($start) ja end ($end) ovat pakollisia parametreja")
    }
  }

  override protected implicit def jsonFormats: Formats = HakurekisteriJsonSupport.format

}