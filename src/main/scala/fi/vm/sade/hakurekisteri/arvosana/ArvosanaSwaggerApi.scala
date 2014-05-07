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

  val arvioFields = Seq(ModelField("arvosana", null, DataType.String, None, AnyValue, required = true),
    ModelField("asteikko", null, DataType.String, None, AllowableValues(Arvio.asteikot.toList), required = true))

  val arvioModel = Model("Arvio", "Arviotiedot", arvioFields.map(t => (t.name, t)).toMap)

  registerModel(arvioModel)

  val fields = Seq(ModelField("suoritus", null, DataType.String, None, AnyValue, required = true),
    ModelField("arvio", null, DataType("Arvio"), None, AnyValue, required = true),
    ModelField("aine", null, DataType.String, None, AnyValue, required = true),
    ModelField("lisatieto", null, DataType.String, None, AnyValue, required = false),
    ModelField("valinnainen", null, DataType.Boolean, Some("false"), AnyValue, required = false))

  val suoritusModel = Model("Arvosana", "Arvosanatiedot", fields.map(t => (t.name, t)).toMap)

  registerModel(suoritusModel)

  val query = (apiOperation[Arvosana]("haeArvosanat")
    summary "Näytä kaikki arvosanat"
    notes "Näyttää kaikki arvosanat. Voit myös hakea eri parametreillä."
    parameter queryParam[Option[String]]("suoritus").description("suorituksen id"))

  val create = apiOperation[Arvosana]("lisääArvosana")
    .parameter(bodyParam[Arvosana]("uusiArvosana").description("Uusi arvosana").required)
    .summary("luo arvosanan ja palauttaa sen tiedot")

  val update =  apiOperation[Arvosana]("päivitäArvosanaa")

   // parameter pathParam[UUID]("id").description("päivitettävän arvosanan id")
}





