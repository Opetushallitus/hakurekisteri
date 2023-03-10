package fi.vm.sade.hakurekisteri.web.opiskeluoikeus

import org.scalatra.swagger.DataType
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriResource, OldSwaggerSyntax}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus

trait OpiskeluoikeusSwaggerApi extends OpiskeluoikeusSwaggerModel {
  this: HakurekisteriResource[Opiskeluoikeus] =>

  protected val applicationDescription = "Opiskeluoikeustietojen rajapinta"

  registerModel(opiskeluoikeusModel)

  val query = apiOperation[Seq[Opiskeluoikeus]]("opiskeluoikeudet")
    .summary("näyttää kaikki opiskeluoikeustiedot")
    .description(
      "Näyttää kaikki opiskeluoikeustiedot. Joko henkilön tai oppilaitoksen tunniste on annettava."
    )
    .parameter(queryParam[Option[String]]("henkilo").description("henkilon oid"))
    .parameter(queryParam[Option[String]]("myontaja").description("myöntäneen oppilaitoksen oid"))
    .tags("opiskeluoikeudet")

  val create = apiOperation[Opiskeluoikeus]("lisääOpiskeluoikeus")
    .summary("luo opiskeluoikeustiedon ja palauttaa sen tiedot")
    .parameter(
      bodyParam[Opiskeluoikeus]("opiskeluoikeus").description("uusi opiskeluoikeustieto").required
    )
    .tags("opiskeluoikeudet")

  val update = apiOperation[Opiskeluoikeus]("päivitäOpiskeluoikeus")
    .summary("päivittää olemassa olevaa opiskeluoikeustietoa ja palauttaa sen tiedot")
    .parameter(pathParam[String]("id").description("opiskeluoikeustiedot uuid").required)
    .parameter(
      bodyParam[Opiskeluoikeus]("opiskeluoikeus")
        .description("päivitettävä opiskeluoikeustieto")
        .required
    )
    .tags("opiskeluoikeudet")

  val read = apiOperation[Opiskeluoikeus]("haeOpiskeluoikeus")
    .summary("hakee opiskeluoikeustiedon tiedot")
    .parameter(pathParam[String]("id").description("opiskeluoikeustiedon uuid").required)
    .tags("opiskeluoikeudet")

  val delete = apiOperation[Unit]("poistaOpiskeluoikeus")
    .summary("poistaa olemassa olevan opiskeluoikeustiedon")
    .parameter(pathParam[String]("id").description("opiskeluoikeustiedon uuid").required)
    .tags("opiskeluoikeudet")
}

trait OpiskeluoikeusSwaggerModel extends OldSwaggerSyntax {

  val opiskeluoikeusFields = Seq(
    ModelField("id", "opiskeluoikeustiedon uuid", DataType.String),
    ModelField("alkuPaiva", null, DataType.Date),
    ModelField("loppuPaiva", null, DataType.Date, required = false),
    ModelField("henkiloOid", null, DataType.String),
    ModelField("komo", null, DataType.String),
    ModelField("myontaja", null, DataType.String)
  )

  def opiskeluoikeusModel = Model(
    "Opiskeluoikeus",
    "Opiskeluoikeustiedot",
    opiskeluoikeusFields.map(t => (t.name, t)).toMap
  )

}
