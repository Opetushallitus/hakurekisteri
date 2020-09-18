package fi.vm.sade.hakurekisteri.web.integration.virta

import fi.vm.sade.hakurekisteri.integration.virta.VirtaStatus
import fi.vm.sade.hakurekisteri.web.rest.support.OldSwaggerSyntax
import org.scalatra.swagger.{DataType, Model, SwaggerSupport}

trait VirtaSwaggerApi extends SwaggerSupport with VirtaSwaggerModel {

  registerModel(virtaStatusModel)

  val process = apiOperation[VirtaStatus]("käynnistäVirtaProsessointi")
    .summary("Käynnistää VIRTA-prosessoinnin")
    .description("Käynnistää virta-prosessoinnin.")
    .tags("virta")

  val refreshOppija = apiOperation[VirtaStatus]("päivitäOppijanTiedot")
    .summary("Päivittää yhden oppijan tiedot VIRTA-järjestelmästä.")
    .description("Päivittää yhden oppijan tiedot VIRTA-järjestelmästä.")
    .parameter(
      pathParam[String]("oppijaOid")
        .description("oppijanumero")
        .required
    )
    .tags("virta")

  val statusQuery = apiOperation[VirtaStatus]("status")
    .summary("Palauttaa VIRTA-integraation tilan.")
    .description("Palauttaa VIRTA-integraation tilan.")
    .tags("virta")
}

trait VirtaSwaggerModel extends OldSwaggerSyntax {

  val virtaStatusFields = Seq(
    ModelField(
      "lastProcessDone",
      "Koska viimeisin käsittely on valmistunut",
      DataType.DateTime,
      required = false
    ),
    ModelField("processing", "Onko käsittely käynnissä", DataType.Boolean, required = false),
    ModelField("queueLength", "Jonon pituus", DataType.Long),
    ModelField("status", "Tila", DataType.String)
  )

  def virtaStatusModel: Model =
    Model("VirtaStatus", "VirtaStatus", virtaStatusFields.map(t => (t.name, t)).toMap)
}
