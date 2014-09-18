package fi.vm.sade.hakurekisteri.suoritus


import org.scalatra.swagger._
import scala.Some
import org.scalatra.swagger.AllowableValues.AnyValue
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriResource
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import java.util.UUID

trait SuoritusSwaggerApi  { this: HakurekisteriResource[Suoritus, CreateSuoritusCommand] =>

  override protected val applicationName = Some("suoritukset")
  protected val applicationDescription = "Suoritustietojen rajapinta"

  val fields = Seq(ModelField("id", "suorituksen uuid", DataType.String, None, AnyValue, required = false),
    ModelField("tila", null, DataType.String, None, AnyValue, required = true),
    ModelField("komo", null, DataType.String, None, AnyValue, required = true),
    ModelField("myontaja", null, DataType.String, None, AnyValue, required = true),
    ModelField("henkiloOid", null, DataType.String, None, AnyValue, required = true),
    ModelField("valmistuminen", null, DataType.Date, None, AnyValue, required = true),
    ModelField("suoritusKieli", null, DataType.String, None, AnyValue, required = true),
    ModelField("yksilollistaminen", null, DataType.String, None, AllowableValues(yksilollistaminen.values.map(v => v.toString).toList)))

  val suoritusModel = Model("Suoritus", "Suoritustiedot", fields.map(t => (t.name, t)).toMap)

  registerModel(suoritusModel)

  val query = apiOperation[Suoritus]("haeSuoritukset")
    .summary("näyttää kaikki suoritukset")
    .notes("Näyttää kaikki suoritukset. Voit myös hakea eri parametreillä.")
    .parameter(queryParam[Option[String]]("henkilo").description("henkilon oid"))
    .parameter(queryParam[Option[String]]("kausi").description("päättymisen kausi").allowableValues("S", "K"))
    .parameter(queryParam[Option[String]]("vuosi").description("päättymisen vuosi"))
    .parameter(queryParam[Option[String]]("myontaja").description("myöntäneen oppilaitoksen oid"))

  val create = apiOperation[Suoritus]("lisääSuoritus")
    .summary("luo suorituksen ja palauttaa sen tiedot")
    .parameter(bodyParam[Suoritus]("suoritus").description("uusi suoritus").required)

  val update =  apiOperation[Suoritus]("päivitäSuoritus")
    .summary("päivittää olemassa olevaa suoritusta ja palauttaa sen tiedot")
    .parameter(pathParam[String]("id").description("suorituksen uuid").required)
    .parameter(bodyParam[Suoritus]("suoritus").description("päivitettävä suoritus").required)

  val read = apiOperation[Suoritus]("haeSuoritus")
    .summary("hakee suorituksen tiedot")
    .parameter(pathParam[String]("id").description("suorituksen uuid").required)

  val delete = apiOperation[Unit]("poistaSuoritus")
    .summary("poistaa olemassa olevan suoritustiedon")
    .parameter(pathParam[String]("id").description("suoritustiedon uuid").required)


}





