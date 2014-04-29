package fi.vm.sade.hakurekisteri.suoritus


import org.scalatra.swagger._
import scala.Some
import org.scalatra.swagger.AllowableValues.AnyValue
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriResource
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import java.util.UUID

trait SuoritusSwaggerApi  { this: HakurekisteriResource[Suoritus, CreateSuoritusCommand] =>

  override protected val applicationName = Some("suoritukset")
  protected val applicationDescription = "Suoritusrekisterin rajapinta."

  val fields = Seq(ModelField("tila", null, DataType.String, None, AnyValue, required = true),
    ModelField("komo", null, DataType.String, None, AnyValue, required = true),
    ModelField("myontaja", null, DataType.String, None, AnyValue, required = true),
    ModelField("luokka", null, DataType.String, None, AnyValue, required = true),
    ModelField("henkiloOid", null, DataType.String, None, AnyValue, required = true),
    ModelField("luokkataso", null, DataType.String, None, AnyValue, required = true),
    ModelField("valmistuminen", null, DataType.Date, None, AnyValue, required = true),
    ModelField("yksilollistaminen", null, DataType.String, None, AllowableValues(yksilollistaminen.values.map(v => v.toString).toList)))

  val suoritusModel = Model("Suoritus", "Suoritustiedot", fields.map(t => (t.name, t)).toMap)

  registerModel(suoritusModel)

  val query = (apiOperation[Suoritus]("haeSuoritukset")
    summary "Näytä kaikki suoritukset"
    notes "Näyttää kaikki suoritukset. Voit myös hakea eri parametreillä."
    parameter queryParam[Option[String]]("henkilo").description("suorittaneen henkilon oid")
    parameter queryParam[Option[String]]("kausi").description("suorituksen päättymisen kausi").allowableValues("S", "K")
    parameter queryParam[Option[String]]("vuosi").description("suorituksen päättymisen vuosi"))

  val create = apiOperation[Suoritus]("lisääSuoritus")
    .parameter(bodyParam[Suoritus]("uusiSuoritus").description("Uusi suoritus").required)
    .summary("luo suorituksen ja palauttaa sen tiedot")

  val update =  apiOperation[Suoritus]("päivitäSuoritusta")

   // parameter pathParam[UUID]("id").description("päivitettävän surituksen id")
}





