package fi.vm.sade.hakurekisteri.arvosana

import org.scalatra.swagger._
import scala.Some
import org.scalatra.swagger.AllowableValues.AnyValue
import fi.vm.sade.hakurekisteri.rest.support.{OldSwaggerSyntax, HakurekisteriResource}

trait ArvosanaSwaggerApi extends OldSwaggerSyntax with ArvosanaSwaggerModel { this: HakurekisteriResource[Arvosana, CreateArvosanaCommand] =>

  protected val applicationDescription = "Arvosanatietojen rajapinta"

  registerModel(arvioModel)
  registerModel(arvosanaModel)

  val query = apiOperation[Seq[Arvosana]]("haeArvosanat")
    .summary("näyttää kaikki arvosanat")
    .notes("Näyttää kaikki arvosanat. Voit myös hakea suorituksella.")
    .parameter(queryParam[Option[String]]("suoritus").description("suorituksen uuid"))

  val create = apiOperation[Arvosana]("lisääArvosana")
    .summary("luo arvosanatiedon ja palauttaa sen tiedot")
    .parameter(bodyParam[Arvosana]("arvosana").description("uusi arvosanatietoa").required)

  val update = apiOperation[Arvosana]("päivitäArvosana")
    .summary("päivittää olemassa olevaa arvosanatietoa ja palauttaa sen tiedot")
    .parameter(pathParam[String]("id").description("arvosanan uuid").required)
    .parameter(bodyParam[Arvosana]("arvosana").description("päivitettävä arvosanatietoa").required)

  val read = apiOperation[Arvosana]("haeArvosana")
    .summary("hakee arvosanatiedon tiedot")
    .parameter(pathParam[String]("id").description("arvosanatiedon uuid").required)

  val delete = apiOperation[Unit]("poistaArvosana")
    .summary("poistaa olemassa olevan arvosanan tiedot")
    .parameter(pathParam[String]("id").description("arvosanatiedon uuid").required)

}

trait ArvosanaSwaggerModel extends OldSwaggerSyntax {
  val arvioFields = Seq(
    ModelField("arvosana", "arvosana", DataType.String),
    ModelField("asteikko", "arvosanan asteikko", DataType.String, Some(Arvio.ASTEIKKO_4_10), AllowableValues(Arvio.asteikot.toList)),
    ModelField("pisteet", "YO-arvosanan pisteet", DataType.Int, required = false))

  def arvioModel = Model("Arvio", "Arvosana", arvioFields.map(t => (t.name, t)).toMap)

  val arvosanaFields = Seq(
    ModelField("id", "arvosanan uuid", DataType.String),
    ModelField("suoritus", "suorituksen uuid", DataType.String),
    ModelField("arvio", "arvosana", DataType("Arvio")),
    ModelField("aine", "aine josta arvosana on annettu", DataType.String),
    ModelField("lisatieto", "aineen lisätieto. esim kieli", DataType.String, required = false),
    ModelField("valinnainen", "onko aine ollut valinnainen", DataType.Boolean, Some("false"), required = false),
    ModelField("myonnetty", "milloin arvosana on myönnetty", DataType.Date, required = false))

  def arvosanaModel = Model("Arvosana", "Arvosanatiedot", arvosanaFields.map(t => (t.name, t)).toMap)
}





