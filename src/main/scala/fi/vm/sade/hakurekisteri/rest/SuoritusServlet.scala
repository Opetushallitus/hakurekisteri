package fi.vm.sade.hakurekisteri.rest

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.pattern.ask

import fi.vm.sade.hakurekisteri.query.SuoritusQuery
import org.scalatra.swagger._
import org.scalatra.{AsyncResult, FutureSupport}
import fi.vm.sade.hakurekisteri.domain.{Komoto, yksilollistaminen, Suoritus}
import scala.Some
import org.scalatra.swagger.AllowableValues.AnyValue
import scala.concurrent.Future
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder

class SuoritusServlet(suoritusActor: ActorRef)(implicit val swagger: Swagger, system: ActorSystem)
  extends HakurekisteriResource[Suoritus](suoritusActor) {

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

  val basicGet = apiOperation("haeSuoritukset", suoritusModel).
    summary("Näytä kaikki suoritukset").
    notes("Näyttää kaikki suoritukset. Voit myös hakea eri parametreillä.").
    parameter(queryParam[Option[String]]("henkilo").description("suorittaneen henkilon oid")).
    parameter(queryParam[Option[String]]("kausi").description("suorituksen päättymisen kausi").allowableValues("S","K")).
    parameter(queryParam[Option[String]]("vuosi").description("suorituksen päättymisen vuosi"))

  readOperation(basicGet, SuoritusQuery(_))


}




