package fi.vm.sade.hakurekisteri.web.opiskelija

import fi.vm.sade.hakurekisteri.opiskelija.OppilaitoksenOpiskelijat
import fi.vm.sade.hakurekisteri.web.rest.support.{IncidentReportSwaggerModel, OldSwaggerSyntax}
import org.scalatra.swagger.{DataType, SwaggerSupport}

trait OppilaitoksenOpiskelijaSwaggerApi
    extends SwaggerSupport
    with IncidentReportSwaggerModel
    with OppilaitoksenOpiskelijatSwaggerModel { this: OppilaitoksenOpiskelijatResource =>

  registerModel(oppilaitoksenOpiskelijatSwaggerModel)

  val query = apiOperation[Seq[OppilaitoksenOpiskelijat]]("oppilaitoksen opiskelijat")
    .summary("Hakee oppilaitoksen opiskelijat ja opiskelijoiden luokat")
    .description("Hakee oppilaitoksen opiskelijat, oidit, ja heidän luokkatietonsa")
    .parameter(pathParam[String]("oppilaitosOid").description("oppilaitoksen oid"))
    .parameter(queryParam[Option[String]]("vuosi").description("vuosi jonka tietoja haetaan"))
    .parameter(
      queryParam[Option[Seq[String]]]("luokkaTasot")
        .description("Luokkatasot millä tietoja heataan")
    )
}

trait OppilaitoksenOpiskelijatSwaggerModel extends OldSwaggerSyntax {

  val oppilaitoksenOpiskelijatFields = Seq(
    ModelField("henkiloOid", null, DataType.String),
    ModelField("luokka", "Opiskelijan luokka", DataType.String)
  )

  def oppilaitoksenOpiskelijatSwaggerModel =
    Model(
      "OppilaitoksenOpiskelijat",
      "Oppilaitoksen opiskelijat luokkatiedoilla",
      oppilaitoksenOpiskelijatFields.map(t => (t.name, t)).toMap
    )
}
