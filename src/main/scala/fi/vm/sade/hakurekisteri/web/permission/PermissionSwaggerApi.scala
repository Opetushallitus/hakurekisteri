package fi.vm.sade.hakurekisteri.web.permission

import fi.vm.sade.hakurekisteri.web.rest.support.{ModelResponseMessage, OldSwaggerSyntax}
import org.scalatra.swagger.{DataType, SwaggerSupport}

trait PermissionSwaggerApi extends OldSwaggerSyntax with SwaggerSupport {

  val permissionRequestFields = Seq(
    ModelField("personOidsForSamePerson", "kohdehenkilön henkilöoidit", DataType.GenList(DataType.String)),
    ModelField("organisationOids", "virkailijan organisaatiot ja niiden lapsiorganisaatiot", DataType.GenList(DataType.String))
  )
  val permissionResponseFields = Seq(
    ModelField("accessAllowed", "onko pääsy sallittu", DataType.Boolean)
  )
  val errorResponseFields = Seq(
    ModelField("errorMessage", "mahdollinen virheviesti virhetilanteessa", DataType.String)
  )

  def permissionRequestModel = Model("PermissionCheckRequest", "PermissionCheckRequest", permissionRequestFields.map(t => (t.name, t)).toMap)
  def permissionResponseModel = Model("PermissionCheckResponse", "PermissionCheckResponse", permissionResponseFields.map(t => (t.name, t)).toMap)
  def permissionErrorResponseModel = Model("PermissionErrorResponse", "PermissionErrorResponse", errorResponseFields.map(t => (t.name, t)).toMap)

  registerModel(permissionRequestModel)
  registerModel(permissionResponseModel)
  registerModel(permissionErrorResponseModel)

  val checkPermission = apiOperation[PermissionCheckResponse]("checkPermission")
    .summary("tarkistaa käyttöoikeuden")
    .description("Tarkistaa onko henkilöllä käyttöoikeus johonkin listatuista organisaatioista. " +
      "Virkailijat annetuista organisaatioista saavat katsella vain ko. organisaatioihin liittettyjen henkilöiden tietoja.")
    .parameter(bodyParam(permissionRequestModel))
    .responseMessage(ModelResponseMessage(400, "virhe kutsussa", Some("PermissionErrorResponse")))
    .responseMessage(ModelResponseMessage(504, "käyttöoikeustarkistusta ei ehditty tehdä määrätyssä ajassa", Some("PermissionErrorResponse")))
    .responseMessage(ModelResponseMessage(500, "virhe käyttöoikeustarkistuksessa", Some("PermissionErrorResponse")))

}
