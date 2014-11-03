package fi.vm.sade.hakurekisteri.hakija

import java.util.UUID

import org.scalatra.swagger._
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import fi.vm.sade.hakurekisteri.rest.support.{IncidentReportSwaggerModel, ModelResponseMessage, ApiFormat, OldSwaggerSyntax}

trait HakijaSwaggerApi extends SwaggerSupport with IncidentReportSwaggerModel with OldSwaggerSyntax {

  val hakutoiveFields = Seq(
    ModelField("hakujno", null, DataType.Int),
    ModelField("oppilaitos", null, DataType.String),
    ModelField("opetuspiste", null, DataType.String, required = false),
    ModelField("opetuspisteennimi", null, DataType.String, required = false),
    ModelField("koulutus", null, DataType.String),
    ModelField("harkinnanvaraisuusperuste", null, DataType.String, required = false),
    ModelField("urheilijanammatillinenkoulutus", null, DataType.String, required = false),
    ModelField("yhteispisteet", null, DataType("double"), required = false),
    ModelField("valinta", null, DataType.String, required = false),
    ModelField("vastaanotto", null, DataType.String, required = false),
    ModelField("lasnaolo", null, DataType.String, required = false),
    ModelField("terveys", null, DataType.String, required = false),
    ModelField("aiempiperuminen", null, DataType.Boolean, required = false),
    ModelField("kaksoistutkinto", null, DataType.Boolean, required = false))

  registerModel(Model("XMLHakutoive", "Hakutoive", hakutoiveFields.map{ t => (t.name, t) }.toMap))

  val hakemusFields = Seq(
    ModelField("vuosi", null, DataType.String),
    ModelField("kausi", null, DataType.String),
    ModelField("hakemusnumero", null, DataType.String),
    ModelField("lahtokoulu", null, DataType.String, required = false),
    ModelField("lahtokoulunnimi", null, DataType.String, required = false),
    ModelField("luokka", null, DataType.String, required = false),
    ModelField("luokkataso", null, DataType.String, required = false),
    ModelField("pohjakoulutus", null, DataType.String),
    ModelField("todistusvuosi", null, DataType.String, required = false),
    ModelField("julkaisulupa", null, DataType.Boolean, required = false),
    ModelField("yhteisetaineet", null, DataType("double"), required = false),
    ModelField("lukiontasapisteet", null, DataType("double"), required = false),
    ModelField("lisapistekoulutus", null, DataType.String, required = false),
    ModelField("yleinenkoulumenestys", null, DataType("double"), required = false),
    ModelField("painotettavataineet", null, DataType("double"), required = false),
    ModelField("hakutoiveet", null, DataType.GenList(DataType("XMLHakutoive"))))

  registerModel(Model("XMLHakemus", "Hakemus", hakemusFields.map{ t => (t.name, t) }.toMap))

  val hakijaFields = Seq(
    ModelField("hetu", null, DataType.String),
    ModelField("oppijanumero", null, DataType.String),
    ModelField("sukunimi", null, DataType.String),
    ModelField("etunimet", null, DataType.String),
    ModelField("kutsumanimi", null, DataType.String, required = false),
    ModelField("lahiosoite", null, DataType.String),
    ModelField("postinumero", null, DataType.String),
    ModelField("maa", null, DataType.String),
    ModelField("kansalaisuus", null, DataType.String),
    ModelField("matkapuhelin", null, DataType.String, required = false),
    ModelField("sahkoposti", null, DataType.String, required = false),
    ModelField("kotikunta", null, DataType.String, required = false),
    ModelField("sukupuoli", null, DataType.String),
    ModelField("aidinkieli", null, DataType.String),
    ModelField("koulutusmarkkinointilupa", null, DataType.Boolean),
    ModelField("hakemus", null, DataType("XMLHakemus")))

  registerModel(Model("XMLHakija", "Hakija", hakijaFields.map{ t => (t.name, t) }.toMap))

  val hakijatFields = Seq(ModelField("hakijat", null, DataType.GenList(DataType("XMLHakija"))))

  registerModel(Model("XMLHakijat", "Hakijat", hakijatFields.map{ t => (t.name, t) }.toMap))

  registerModel(incidentReportModel)

  val query: OperationBuilder = apiOperation[XMLHakijat]("haeHakijat")
    .summary("näyttää kaikki hakijat")
    .notes("Näyttää listauksen hakeneista/valituista/paikan vastaanottaneista hakijoista parametrien mukaisesti.")
    .parameter(queryParam[Option[String]]("haku").description("haun oid").optional)
    .parameter(queryParam[Option[String]]("organisaatio").description("koulutuksen tarjoajan tai sen yläorganisaation oid").optional)
    .parameter(queryParam[Option[String]]("hakukohdekoodi").description("hakukohdekoodi").optional)
    .parameter(queryParam[String]("hakuehto").description("hakuehto").allowableValues(Hakuehto.values.toList).required)
    .parameter(queryParam[String]("tyyppi").description("tietotyyppi").allowableValues(ApiFormat.values.toList).required)
    .parameter(queryParam[Option[Boolean]]("tiedosto").description("palautetaanko vastaus tiedostona").optional)
    .produces("application/json", "application/xml", "application/octet-stream")
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "internal server error"))

}
