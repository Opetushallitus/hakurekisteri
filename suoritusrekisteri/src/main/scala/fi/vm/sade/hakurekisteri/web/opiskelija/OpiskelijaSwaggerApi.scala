package fi.vm.sade.hakurekisteri.web.opiskelija

import java.util.Date
import org.scalatra.swagger._
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriResource, OldSwaggerSyntax}
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija

trait OpiskelijaSwaggerApi extends OpiskelijaSwaggerModel {
  this: HakurekisteriResource[Opiskelija] =>

  protected val applicationDescription = "Opiskelijatietojen rajapinta"

  registerModel(opiskelijaModel)

  val query = apiOperation[Seq[Opiskelija]]("opiskelijat")
    .summary("näyttää kaikki opiskelijatiedot")
    .description("Näyttää kaikki opiskelijatiedot. Voit myös hakea eri parametreillä.")
    .parameter(queryParam[Option[String]]("henkilo").description("henkilon oid"))
    .parameter(
      queryParam[Option[String]]("kausi")
        .description("kausi jonka tietoja haetaan")
        .allowableValues("S", "K")
    )
    .parameter(queryParam[Option[String]]("vuosi").description("vuosi jonka tietoja haetaan"))
    .parameter(queryParam[Option[Date]]("paiva").description("päivä jonka tietoja haetaan"))
    .parameter(queryParam[Option[String]]("oppilaitosOid").description("oppilaitoksen oid"))
    .parameter(queryParam[Option[String]]("luokka").description("luokan nimi"))
    .tags("opiskelijat")

  val create = apiOperation[Opiskelija]("lisääOpiskelija")
    .summary("luo opiskelijatiedon ja palauttaa sen tiedot")
    .parameter(bodyParam[Opiskelija]("opiskelija").description("uusi opiskelijatieto").required)
    .tags("opiskelijat")

  val update = apiOperation[Opiskelija]("päivitäOpiskelija")
    .summary("päivittää olemassa olevaa opiskelijatietoa ja palauttaa sen tiedot")
    .parameter(pathParam[String]("id").description("opiskelijatiedon uuid").required)
    .parameter(
      bodyParam[Opiskelija]("opiskelija").description("päivitettävä opiskelijatieto").required
    )
    .tags("opiskelijat")

  val read = apiOperation[Opiskelija]("haeOpiskelija")
    .summary("hakee opiskelijatiedon tiedot")
    .parameter(pathParam[String]("id").description("opiskelijatiedon uuid").required)
    .tags("opiskelijat")

  val delete = apiOperation[Unit]("poistaOpiskelija")
    .summary("poistaa olemassa olevan opiskelutiedon")
    .parameter(pathParam[String]("id").description("opiskelutiedon uuid").required)
    .tags("opiskelijat")

}

trait OpiskelijaSwaggerModel extends OldSwaggerSyntax {

  val opiskelijaFields = Seq(
    ModelField("id", "opiskelijatiedon uuid", DataType.String),
    ModelField("oppilaitosOid", null, DataType.String),
    ModelField("luokkataso", null, DataType.String),
    ModelField("luokka", null, DataType.String),
    ModelField("henkiloOid", null, DataType.String),
    ModelField("alkuPaiva", null, DataType.Date),
    ModelField("loppuPaiva", null, DataType.Date, required = false)
  )

  def opiskelijaModel =
    Model("Opiskelija", "Opiskelijatiedot", opiskelijaFields.map(t => (t.name, t)).toMap)

}
