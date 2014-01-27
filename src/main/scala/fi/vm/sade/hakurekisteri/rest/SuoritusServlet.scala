package fi.vm.sade.hakurekisteri.rest

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.pattern.ask

import _root_.akka.util.Timeout
import org.json4s.{Formats, DefaultFormats}
import org.scalatra.json._
import scala.concurrent.ExecutionContext
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.domain.Suoritus
import fi.vm.sade.hakurekisteri.query.SuoritusQuery
import org.scalatra.swagger._
import org.scalatra.{AsyncResult, FutureSupport}

class SuoritusServlet(system: ActorSystem, suoritusActor: ActorRef)(implicit val swagger: Swagger) extends HakuJaValintarekisteriStack
  with SwaggerSupport with JacksonJsonSupport  with FutureSupport  {

  override protected val applicationName = Some("suoritukset")
  protected val applicationDescription = "Suoritusrekisterin rajapinta."


  protected implicit def executor: ExecutionContext = system.dispatcher

  protected implicit val jsonFormats: Formats = DefaultFormats

  val timeout = 10

  implicit val defaultTimeout = Timeout(timeout)

  before() {
    contentType = formats("json")
  }

  val haeSuoritukset =
    (apiOperation[Seq[Suoritus]]("haeSuoritukset")
      summary "Näytä kaikki suoritukset"
      notes "Näyttää kaikki suoritukset. Voit myös hakea eri parametreillä."
      parameter queryParam[Option[String]]("henkilo").description("suorittaneen henkilon oid")
      parameter queryParam[Option[String]]("kausi").description("suorituksen päättymisen kausi")
      parameter queryParam[Option[String]]("vuosi").description("suorituksen päättymisen vuosi"))

  get("/", operation(haeSuoritukset)) {
    logger.debug("hae suoritukset")
    new AsyncResult() {
      val is = suoritusActor ? SuoritusQuery(params)
    }
  }

  post("/") {
    new AsyncResult() {
      val is = suoritusActor ? parsedBody.extract[Suoritus]
    }
  }

}


