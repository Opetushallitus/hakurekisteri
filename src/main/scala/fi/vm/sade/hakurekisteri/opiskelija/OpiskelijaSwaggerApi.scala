package fi.vm.sade.hakurekisteri.opiskelija

import fi.vm.sade.hakurekisteri.rest.support.{OldSwaggerSyntax, HakurekisteriResource}
import java.util.Date
import org.scalatra.swagger._
import org.scalatra.swagger.AllowableValues.AnyValue
import scala.Some

trait OpiskelijaSwaggerApi extends OldSwaggerSyntax
    { this: HakurekisteriResource[Opiskelija, CreateOpiskelijaCommand] =>

  override protected val applicationName = Some("opiskelijat")
  protected val applicationDescription = "Opiskelijatietojen rajapinta"





  val fields = Seq(ModelField("id", "opiskelijatiedon uuid", DataType.String, None, AnyValue, required = false),
    ModelField("oppilaitosOid", null, DataType.String, None, AnyValue, required = true),
    ModelField("luokkataso", null, DataType.String, None, AnyValue, required = true),
    ModelField("luokka", null, DataType.String, None, AnyValue, required = true),
    ModelField("henkiloOid", null, DataType.String, None, AnyValue, required = true),
    ModelField("alkuPaiva", null, DataType.Date, None, AnyValue, required = true),
    ModelField("loppuPaiva", null, DataType.Date, None, AnyValue, required = false))




  val opiskelijaModel = Model("Opiskelija", "Opiskelijatiedot", fields.map(t => (t.name, t)).toMap)

  registerModel(opiskelijaModel)

  val query = apiOperation[Seq[Opiskelija]]("opiskelijat")
    .summary("näyttää kaikki opiskelijatiedot")
    .notes("Näyttää kaikki opiskelijatiedot. Voit myös hakea eri parametreillä.")
    .parameter(queryParam[Option[String]]("henkilo").description("henkilon oid"))
    .parameter(queryParam[Option[String]]("kausi").description("kausi jonka tietoja haetaan").allowableValues("S", "K"))
    .parameter(queryParam[Option[String]]("vuosi").description("vuosi jonka tietoja haetaan"))
    .parameter(queryParam[Option[Date]]("paiva").description("päivä jonka tietoja haetaan"))
    .parameter(queryParam[Option[String]]("oppilaitosOid").description("oppilaitoksen oid"))
    .parameter(queryParam[Option[String]]("luokka").description("luokan nimi"))

  val create = apiOperation[Opiskelija]("lisääOpiskelija")
    .summary("luo opiskelijatiedon ja palauttaa sen tiedot")
    .parameter(bodyParam[Opiskelija]("opiskelija").description("uusi opiskelijatieto").required)

  val update = apiOperation[Opiskelija]("päivitäOpiskelija")
    .summary("päivittää olemassa olevaa opiskelijatietoa ja palauttaa sen tiedot")
    .parameter(pathParam[String]("id").description("opiskelijatiedon uuid").required)
    .parameter(bodyParam[Opiskelija]("opiskelija").description("päivitettävä opiskelijatieto").required)

  val read = apiOperation[Opiskelija]("haeOpiskelija")
    .summary("hakee opiskelijatiedon tiedot")
    .parameter(pathParam[String]("id").description("opiskelijatiedon uuid").required)

  val delete = apiOperation[Unit]("poistaOpiskelija")
    .summary("poistaa olemassa olevan opiskelutiedon")
    .parameter(pathParam[String]("id").description("opiskelutiedon uuid").required)

}
