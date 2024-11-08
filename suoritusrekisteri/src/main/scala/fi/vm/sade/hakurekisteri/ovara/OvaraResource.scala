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
import org.scalatra.swagger.{Swagger, SwaggerEngine}
import org.slf4j.LoggerFactory

import java.util.UUID
import scala.util.Try

class OvaraResource(ovaraService: OvaraService)(implicit val security: Security, sw: Swagger)
    extends ScalatraServlet
    with JValueResult
    with JacksonJsonSupport
    with SessionSupport
    with SecuritySupport
    with Logging {
  val audit: Audit = SuoritusAuditVirkailija.audit

  protected implicit def swagger: SwaggerEngine[_] = sw

  get("/muodosta") {
    if (currentUser.exists(_.isAdmin)) {
      val start = params.get("start").map(_.toLong)
      val end = params.get("end").map(_.toLong)
      (start, end) match {
        case (Some(start), Some(end)) =>
          logger.info(s"Muodostetaan siirtotiedosto! $start - $end")
          val result = ovaraService.formSiirtotiedostotPaged(
            SiirtotiedostoProcess(
              -1,
              UUID.randomUUID().toString,
              start,
              end,
              "start",
              None,
              SiirtotiedostoProcessInfo(Map.empty, Seq.empty),
              finishedSuccessfully = false,
              None,
              ensikertalaisuudetFormedToday =
                true //Ei muodosteta ensikertalaisuuksia, sitä varten on erillinen rajapinta.
            )
          )
          Ok(s"$result")
        case _ =>
          logger.error(s"Toinen pakollisista parametreista (start $start, end $end) puuttuu!")
          BadRequest(s"Start ($start) ja end ($end) ovat pakollisia parametreja")
      }
    } else {
      Forbidden("Ei tarvittavia oikeuksia ovara-siirtotiedoston muodostamiseen")
    }

  }

  get("/muodosta/paivittaiset") {
    if (currentUser.exists(_.isAdmin)) {
      val executionId = UUID.randomUUID().toString
      val vainAktiiviset: Boolean = params.get("vainAktiiviset").exists(_.toBoolean)
      val ensikertalaisuudet: Boolean = params.get("ensikertalaisuudet").exists(_.toBoolean)
      val harkinnanvaraisuudet: Boolean = params.get("harkinnanvaraisuudet").exists(_.toBoolean)
      val proxySuoritukset: Boolean = params.get("proxySuoritukset").exists(_.toBoolean)
      val combinedParams = DailyProcessingParams(
        executionId,
        vainAktiiviset,
        ensikertalaisuudet = ensikertalaisuudet,
        harkinnanvaraisuudet = harkinnanvaraisuudet,
        proxySuoritukset = proxySuoritukset
      )
      logger.info(
        s"$executionId Muodostetaan päivittäiset siirtotiedostot. $combinedParams"
      )
      val result = ovaraService.triggerDailyProcessing(combinedParams)
      Ok(s"$executionId Valmista - $result")
    } else {
      Forbidden("Ei tarvittavia oikeuksia ovara-siirtotiedoston muodostamiseen")
    }
  }

  override protected implicit def jsonFormats: Formats = HakurekisteriJsonSupport.format

}
