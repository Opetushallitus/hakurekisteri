package fi.vm.sade.hakurekisteri.rest

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.pattern.ask

import _root_.akka.util.Timeout
import scala.concurrent.ExecutionContext
import fi.vm.sade.hakurekisteri.query.SuoritusQuery
import org.scalatra.swagger._
import org.scalatra.{ScalatraServlet, AsyncResult, FutureSupport}
import fi.vm.sade.hakurekisteri.domain.{Komoto, yksilollistaminen, Suoritus}
import scala.Some
import org.scalatra.swagger.AllowableValues.AnyValue

class SuoritusServlet(system: ActorSystem, suoritusActor: ActorRef)(implicit val swagger: Swagger) extends HakurekisteriResource
     with FutureSupport {

  override protected val applicationName = Some("suoritukset")
  protected val applicationDescription = "Suoritusrekisterin rajapinta."


  protected implicit def executor: ExecutionContext = system.dispatcher



  val timeout = 10

  implicit val defaultTimeout = Timeout(timeout)

  before() {
    contentType = formats("json")
  }

  val fields = Seq(ModelField("tila",null,DataType.String,None,AnyValue,required = true),
                   ModelField("komoto",null,DataType("Komoto"),None,AnyValue, required = true),
                   ModelField("luokka",null,DataType.String,None,AnyValue,required = true),
                   ModelField("henkiloOid",null,DataType.String,None,AnyValue,required = true),
                   ModelField("luokkataso",null,DataType.String,None,AnyValue,required = true),
                   ModelField("valmistuminen",null,DataType.Date,None,AnyValue,required = true),
                   ModelField("yksilollistaminen", null, DataType.String, None , AllowableValues(yksilollistaminen.values map {v => v.toString} toList)))

  val suoritusModel = Model("Suoritus", "Suoritustiedot", fields map { t => (t.name, t) } toMap)

  registerModel[Komoto]
  registerModel(suoritusModel)

  val haeSuoritukset =
    (apiOperation("haeSuoritukset", suoritusModel)
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




