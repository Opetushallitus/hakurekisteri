package fi.vm.sade.hakurekisteri.opiskelija

import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriResource
import scala.Some
import java.util.Date
import org.scalatra.swagger.{DataType, ModelField, Model}
import org.scalatra.swagger.AllowableValues.AnyValue

trait OpiskelijaSwaggerApi
    { this: HakurekisteriResource[Opiskelija, CreateOpiskelijaCommand] =>

  override protected val applicationName = Some("opiskelijat")
  protected val applicationDescription = "Opiskelijatietojen rajapinta."

  val fields = Seq(ModelField("id", "opiskelijatiedon uuid", DataType.String, None, AnyValue, required = false),
    ModelField("oppilaitosOid", null, DataType.String, None, AnyValue, required = true),
    ModelField("luokkataso", null, DataType.String, None, AnyValue, required = true),
    ModelField("luokka", null, DataType.String, None, AnyValue, required = true),
    ModelField("henkiloOid", null, DataType.String, None, AnyValue, required = true),
    ModelField("alkuPaiva", null, DataType.Date, None, AnyValue, required = true),
    ModelField("loppuPaiva", null, DataType.Date, None, AnyValue, required = false))
  val opiskelijaModel = Model("Opiskelija", "Opiskelijatiedot", fields.map(t => (t.name, t)).toMap)

  registerModel(opiskelijaModel)

  val query = (apiOperation[Seq[Opiskelija]]("opiskelijat")
    summary "Näytä kaikki opiskelijatiedot"
    notes "Näyttää kaikki opiskelijatiedot. Voit myös hakea eri parametreillä."
    parameter queryParam[Option[String]]("henkilo").description("haetun henkilon oid")
    parameter queryParam[Option[String]]("kausi").description("kausi jonka tietoja haetaan").allowableValues("S", "K")
    parameter queryParam[Option[String]]("vuosi").description("vuosi jonka tietoja haetaan")
    parameter queryParam[Option[Date]]("paiva").description("päivä jonka tietoja haetaan")
    parameter queryParam[Option[String]]("oppilaitosOid").description("haetun oppilaitoksen oid")
    parameter queryParam[Option[String]]("luokka").description("haetun luokan nimi")
  )

  val create = apiOperation[Opiskelija]("lisääOpiskelija")
    .parameter(bodyParam[Opiskelija]("lisääOpiskelija").description("Uusi opiskelija").required)
    .summary("luo opiskelijan ja palauttaa sen tiedot")

  val update = apiOperation[Opiskelija]("päivitäOpiskelijaa") // parameter pathParam[UUID]("id").description("päivitettävän opiskelijan id")
}
