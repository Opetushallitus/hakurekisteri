package fi.vm.sade.hakurekisteri.web.kkhakija

import java.io.OutputStream
import java.text.{ParseException, SimpleDateFormat}
import java.util.{Calendar, Date}

import akka.actor.{ActorRef, ActorSystem}
import akka.event.{Logging, LoggingAdapter}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.hakija.Hakuehto._
import fi.vm.sade.hakurekisteri.hakija.{Hakuehto, Kevat, Lasna, Lasnaolo, Poissa, Puuttuu, Syksy}
import fi.vm.sade.hakurekisteri.integration.hakemus.{FullHakemus, HakemusAnswers, HakemusHenkilotiedot, Koulutustausta, PreferenceEligibility, _}
import fi.vm.sade.hakurekisteri.integration.haku.{GetHaku, Haku, HakuNotFoundException}
import fi.vm.sade.hakurekisteri.integration.koodisto.{GetKoodi, GetRinnasteinenKoodiArvoQuery, Koodi}
import fi.vm.sade.hakurekisteri.integration.tarjonta._
import fi.vm.sade.hakurekisteri.integration.valintatulos.Valintatila.Valintatila
import fi.vm.sade.hakurekisteri.integration.valintatulos.Vastaanottotila.Vastaanottotila
import fi.vm.sade.hakurekisteri.integration.valintatulos.{Ilmoittautumistila, SijoitteluTulos, ValintaTulosQuery, Valintatila}
import fi.vm.sade.hakurekisteri.integration.ytl.YoTutkinto
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.suoritus.{SuoritysTyyppiQuery, VirallinenSuoritus}
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.hakija.HakijaResourceSupport
import fi.vm.sade.hakurekisteri.web.kkhakija.KkHakijaUtil._
import fi.vm.sade.hakurekisteri.web.rest.support.ApiFormat.ApiFormat
import fi.vm.sade.hakurekisteri.web.rest.support.{ApiFormat, IncidentReport, _}
import org.joda.time.DateTime
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine}
import org.scalatra.util.RicherString._

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.Try

class KkHakijaResourceV3(kkHakijaService: KkHakijaService)(implicit system: ActorSystem, sw: Swagger, val security: Security, val ct: ClassTag[Seq[Hakija]])
    extends HakuJaValintarekisteriStack with KkHakijaSwaggerApi with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport
    with SecuritySupport with ExcelSupport[Seq[Hakija]] with DownloadSupport with QueryLogging with HakijaResourceSupport {

  protected def applicationDescription: String = "Korkeakouluhakijatietojen rajapinta"
  protected implicit def swagger: SwaggerEngine[_] = sw
  override protected implicit def executor: ExecutionContext = system.dispatcher
  override val logger: LoggingAdapter = Logging.getLogger(system, this)
  implicit val defaultTimeout: Timeout = 120.seconds
  override protected def renderPipeline: RenderPipeline = renderExcel orElse super.renderPipeline
  override val streamingRender: (OutputStream, Seq[Hakija]) => Unit = KkExcelUtilV3.write

  get("/", operation(query)) {
    val q = KkHakijaQuery(params, currentUser)
    val tyyppi = getFormatFromTypeParam()
    if (q.oppijanumero.isEmpty && q.hakukohde.isEmpty) throw KkHakijaParamMissingException
    val thisResponse= response
    val kkhakijatFuture = kkHakijaService.getKkHakijat(q).flatMap {
      case result if Try(params("tiedosto").toBoolean).getOrElse(false) || tyyppi == ApiFormat.Excel =>
        setContentDisposition(tyyppi, thisResponse, "hakijat")
        Future.successful(result)
      case result => Future.successful(result)
    }
    prepareAsyncResult(tyyppi, kkhakijatFuture)
  }

  incident {
    case KkHakijaParamMissingException => (id) => BadRequest(IncidentReport(id, "either parameter oppijanumero or hakukohde must be given"))
    case t: TarjontaException => (id) => InternalServerError(IncidentReport(id, s"error with tarjonta: $t"))
    case t: HakuNotFoundException => (id) => NotFound(IncidentReport(id, s"$t"))
    case t: InvalidSyntymaaikaException => (id) => InternalServerError(IncidentReport(id, s"error: $t"))
    case t: InvalidKausiException => (id) => InternalServerError(IncidentReport(id, s"error: $t"))
  }

}


