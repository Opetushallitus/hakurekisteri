package fi.vm.sade.hakurekisteri.web.suoritus

import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import org.scalatra.swagger._
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriResource, OldSwaggerSyntax}
import fi.vm.sade.hakurekisteri.suoritus.{yksilollistaminen, Suoritus}

trait SuoritusSwaggerApi extends SuoritusSwaggerModel { this: HakurekisteriResource[Suoritus] =>

  protected val applicationDescription = "Suoritustietojen rajapinta"

  //registerModel(suoritusModel)
  registerModel(virallinenSuoritusModel)
  registerModel(vapaamuotoinenSuoritusModel)

  val query = apiOperation[Seq[Suoritus]]("haeSuoritukset")
    .summary("näyttää kaikki suoritukset")
    .description("Näyttää kaikki suoritukset. Voit myös hakea eri parametreillä.")
    .parameter(queryParam[Option[String]]("henkilo").description("henkilon oid"))
    .parameter(
      queryParam[Option[String]]("kausi").description("päättymisen kausi").allowableValues("S", "K")
    )
    .parameter(queryParam[Option[String]]("vuosi").description("päättymisen vuosi"))
    .parameter(queryParam[Option[String]]("myontaja").description("myöntäneen oppilaitoksen oid"))
    .parameter(queryParam[Option[String]]("komo").description("koulutusmoduulin oid"))
    .parameter(
      queryParam[Option[String]]("muokattuJalkeen")
        .description("ISO aikaleima (esim. 2015-01-01T12:34:56.000+02:00) jonka jälkeen muokatut")
    )
    .parameter(
      queryParam[Option[String]]("muokattuEnnen")
        .description("ISO aikaleima (esim. 2015-01-01T12:34:56.000+02:00) jota ennen muokatut")
    )
    .tags("suoritukset")

  val create = apiOperation[Suoritus]("lisääSuoritus")
    .summary("luo suorituksen ja palauttaa sen tiedot")
    .parameter(bodyParam[Suoritus]("suoritus").description("uusi suoritus").required)
    .tags("suoritukset")

  val update = apiOperation[Suoritus]("päivitäSuoritus")
    .summary("päivittää olemassa olevaa suoritusta ja palauttaa sen tiedot")
    .parameter(pathParam[String]("id").description("suorituksen uuid").required)
    .parameter(bodyParam[Suoritus]("suoritus").description("päivitettävä suoritus").required)
    .tags("suoritukset")

  val read = apiOperation[Suoritus]("haeSuoritus")
    .summary("hakee suorituksen tiedot")
    .parameter(pathParam[String]("id").description("suorituksen uuid").required)
    .tags("suoritukset")

  val delete = apiOperation[Unit]("poistaSuoritus")
    .summary("poistaa olemassa olevan suoritustiedon")
    .parameter(pathParam[String]("id").description("suoritustiedon uuid").required)
    .tags("suoritukset")

}

trait SuoritusSwaggerModel extends OldSwaggerSyntax {

  val suoritusFields = Seq(ModelField("suoritusTyyppi", null, DataType.String))

  def suoritusModel = Model(
    "Suoritus",
    "Suoritus",
    suoritusFields.map(t => (t.name, t)).toMap,
    discriminator = Some("suoritusTyyppi")
  )

  val suoritusLahdeArvotFields = Seq(
    ModelField(
      "hasCompletedMandatoryExams",
      "Pakolliset kokeet suoritettu",
      DataType.String,
      required = false
    )
  )

  def suoritusLahdeArvotModel = Model(
    "SuoritusLahdeArvot",
    "lähdejärjestelmän arvot",
    suoritusLahdeArvotFields.map(t => (t.name, t)).toMap
  )

  val virallinenSuoritusFields = Seq(
    ModelField("id", "suorituksen uuid", DataType.String),
    ModelField("tila", null, DataType.String),
    ModelField("komo", null, DataType.String),
    ModelField("myontaja", null, DataType.String),
    ModelField("henkiloOid", null, DataType.String),
    ModelField("valmistuminen", null, DataType.Date),
    ModelField("suoritusKieli", null, DataType.String),
    ModelField(
      "yksilollistaminen",
      null,
      DataType.String,
      None,
      AllowableValues(yksilollistaminen.values.map(v => v.toString).toList)
    ),
    ModelField("vahvistettu", null, DataType.Boolean, Some("true"), required = false),
    ModelField(
      "lahdeArvot",
      "lähdejärjestelmästä saadut alkuperäiset arvot",
      DataType("SuoritusLahdeArvot"),
      required = false
    )
  )

  def virallinenSuoritusModel = Model(
    "Suoritus",
    "Suoritus",
    virallinenSuoritusFields.map(t => (t.name, t)).toMap,
    Some("Suoritus")
  )

  val vapaamuotoinenSuoritusFields = Seq(
    ModelField("id", "suorituksen uuid", DataType.String, required = false),
    ModelField("kuvaus", null, DataType.String),
    ModelField("myontaja", null, DataType.String),
    ModelField("vuosi", null, DataType.Int),
    ModelField("tyyppi", null, DataType.String),
    ModelField("index", null, DataType.Int),
    ModelField(
      "vahvistettu",
      "onko suoritus vahvistettu, ei voida asettaa arvoon true vapaamuotoiselle suoritukselle",
      DataType.Boolean,
      Some("false"),
      required = false
    )
  )

  def vapaamuotoinenSuoritusModel = Model(
    "VapaamuotoinenSuoritus",
    "VapaamuotoinenSuoritus",
    virallinenSuoritusFields.map(t => (t.name, t)).toMap,
    Some("Suoritus")
  )

}
