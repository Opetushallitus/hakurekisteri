package fi.vm.sade.hakurekisteri.suoritus

import _root_.akka.actor.{ActorRef, ActorSystem}

import org.scalatra.swagger._
import scala.Some
import org.scalatra.swagger.AllowableValues.AnyValue
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriResource
import fi.vm.sade.hakurekisteri.rest.support.Query
import fi.vm.sade.hakurekisteri.rest.support.Kausi.Kausi

trait SuoritusServlet  { this: HakurekisteriResource[Suoritus] =>

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

  read(
    apiOperation[Suoritus]("haeSuoritukset")
      summary "Näytä kaikki suoritukset"
      notes "Näyttää kaikki suoritukset. Voit myös hakea eri parametreillä."
      parameter queryParam[Option[String]]("henkilo").description("suorittaneen henkilon oid")
      parameter queryParam[Option[String]]("kausi").description("suorituksen päättymisen kausi").allowableValues("S", "K")
      parameter queryParam[Option[String]]("vuosi").description("suorituksen päättymisen vuosi"))

  create(apiOperation[Suoritus]("lisääSuoritus")
    .parameter(bodyParam[Suoritus]("uusiSuoritus").description("Uusi suoritus").required)
    .summary("luo Suorituksen ja palauttaa sen tiedot"))


}





