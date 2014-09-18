package fi.vm.sade.hakurekisteri.opiskeluoikeus

import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriResource
import org.scalatra.swagger.AllowableValues.AnyValue
import org.scalatra.swagger.{Model, DataType, ModelField}

trait OpiskeluoikeusSwaggerApi {
  this: HakurekisteriResource[Opiskeluoikeus, CreateOpiskeluoikeusCommand] =>

  override protected val applicationName = Some("opiskeluoikeudet")
  protected val applicationDescription = "Opiskeluoikeustietojen rajapinta"

  val fields = Seq(ModelField("id", "opiskeluoikeustiedon uuid", DataType.String, None, AnyValue, required = false),
    ModelField("alkuPaiva", null, DataType.Date, None, AnyValue, required = true),
    ModelField("loppuPaiva", null, DataType.Date, None, AnyValue, required = false),
    ModelField("henkiloOid", null, DataType.String, None, AnyValue, required = true),
    ModelField("komo", null, DataType.String, None, AnyValue, required = true),
    ModelField("myontaja", null, DataType.String, None, AnyValue, required = true))

  val opiskeluoikeusModel = Model("Opiskeluoikeus", "Opiskeluoikeustiedot", fields.map(t => (t.name, t)).toMap)

  registerModel(opiskeluoikeusModel)

  val query = apiOperation[Seq[Opiskeluoikeus]]("opiskeluoikeudet")
    .summary("näyttää kaikki opiskeluoikeustiedot")
    .notes("Näyttää kaikki opiskeluoikeustiedot. Voit myös hakea eri parametreillä.")
    .parameter(queryParam[Option[String]]("henkilo").description("henkilon oid"))
    .parameter(queryParam[Option[String]]("myontaja").description("myöntäneen oppilaitoksen oid"))

  val create = apiOperation[Opiskeluoikeus]("lisääOpiskeluoikeus")
    .summary("luo opiskeluoikeustiedon ja palauttaa sen tiedot")
    .parameter(bodyParam[Opiskelija]("opiskeluoikeus").description("uusi opiskeluoikeustieto").required)

  val update = apiOperation[Opiskeluoikeus]("päivitäOpiskeluoikeus")
    .summary("päivittää olemassa olevaa opiskeluoikeustietoa ja palauttaa sen tiedot")
    .parameter(pathParam[String]("id").description("opiskeluoikeustiedot uuid").required)
    .parameter(bodyParam[Opiskelija]("opiskeluoikeus").description("päivitettävä opiskeluoikeustieto").required)

  val read = apiOperation[Opiskeluoikeus]("haeOpiskeluoikeus")
    .summary("hakee opiskeluoikeustiedon tiedot")
    .parameter(pathParam[String]("id").description("opiskeluoikeustiedon uuid").required)

  val delete = apiOperation[Unit]("poistaOpiskeluoikeus")
    .summary("poistaa olemassa olevan opiskeluoikeustiedon")
    .parameter(pathParam[String]("id").description("opiskeluoikeustiedon uuid").required)
}
