package fi.vm.sade.hakurekisteri.arvosana

import org.scalatra.swagger._
import scala.Some
import org.scalatra.swagger.AllowableValues.AnyValue
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriResource
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import java.util.UUID

trait ArvosanaSwaggerApi  { this: HakurekisteriResource[Arvosana, CreateArvosanaCommand] =>

  override protected val applicationName = Some("arvosanat")
  protected val applicationDescription = "Arvosanojen rajapinta."

  val arvioFields = Seq(ModelField("arvosana", "arvosana", DataType.String),
    ModelField("asteikko", "arvosanan asteikko", DataType.String, Some("4-10"), AllowableValues(Arvio.asteikot.toList)))

  val arvioModel = Model("Arvio", "Arviotiedot", arvioFields.map(t => (t.name, t)).toMap)

  registerModel(arvioModel)

  val fields = Seq(ModelField("suoritus", "suorituksen uuid", DataType.String),
    ModelField("arvio", "arvosana", DataType("Arvio")),
    ModelField("aine", "aine josta arvosana on annettu", DataType.String),
    ModelField("lisatieto", "aineen lisätieto. esim kieli", DataType.String, required = false),
    ModelField("valinnainen", "onko aine ollut valinnainen", DataType.Boolean, Some("false"), required = false))

  val arvosanaModel = Model("Arvosana", "Arvosanatiedot", fields.map(t => (t.name, t)).toMap)

  registerModel(arvosanaModel)

  val query = (apiOperation[Arvosana]("haeArvosanat")
    summary "Näytä kaikki arvosanat"
    notes "Näyttää kaikki arvosanat. Voit myös hakea suorituksella."
    parameter queryParam[Option[String]]("suoritus").description("suorituksen uuid"))

  val create = apiOperation[Arvosana]("lisääArvosana")
    .parameter(bodyParam[Arvosana]("uusiArvosana").description("Uusi arvosana").required)
    .summary("luo arvosanan ja palauttaa sen tiedot")

  val update =  apiOperation[Arvosana]("päivitäArvosanaa")

   // parameter pathParam[UUID]("id").description("päivitettävän arvosanan id")
}





