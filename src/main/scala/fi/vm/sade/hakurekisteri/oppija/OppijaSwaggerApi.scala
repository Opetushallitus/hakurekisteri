package fi.vm.sade.hakurekisteri.oppija

import org.scalatra.swagger.SwaggerSupport
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder

trait OppijaSwaggerApi extends SwaggerSupport {

  override protected val applicationName = Some("rest/v1/oppijat")
  
  val query: OperationBuilder = apiOperation[Seq[Oppija]]("haeOppijat")
    .summary("näyttää oppijat")
    .notes("Näyttää listauksen oppijoista parametrien mukaisesti.")
    .parameter(queryParam[Option[String]]("haku").description("haun oid").optional)
    .parameter(queryParam[Option[String]]("organisaatio").description("koulutuksen tarjoajan tai sen yläorganisaation oid").optional)
    .parameter(queryParam[Option[String]]("hakukohde").description("hakukohteen oid").optional)
  
  val read: OperationBuilder = apiOperation[Oppija]("haeOppija")
    .summary("näyttää yhden oppijan tiedot")
    .notes("Näyttää yhden oppijan tiedot oppijanumeron perusteella.")
    .parameter(pathParam[String]("oid").description("oppijanumero").required)

}
