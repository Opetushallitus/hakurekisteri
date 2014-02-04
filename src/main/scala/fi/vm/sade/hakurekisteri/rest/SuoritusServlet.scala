package fi.vm.sade.hakurekisteri.rest

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.pattern.ask

import fi.vm.sade.hakurekisteri.query.SuoritusQuery
import org.scalatra.swagger._
import org.scalatra.{AsyncResult, FutureSupport}
import fi.vm.sade.hakurekisteri.domain.{Komoto, yksilollistaminen, Suoritus}
import scala.Some
import org.scalatra.swagger.AllowableValues.AnyValue

class SuoritusServlet(suoritusActor: ActorRef)(implicit val swagger: Swagger, system: ActorSystem) extends HakurekisteriResource(system)
     with FutureSupport {

  override protected val applicationName = Some("suoritukset")
  protected val applicationDescription = "Suoritusrekisterin rajapinta."




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
      parameter queryParam[Option[String]]("kausi").description("suorituksen päättymisen kausi").allowableValues("S","K")
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




