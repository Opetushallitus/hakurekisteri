package fi.vm.sade.hakurekisteri.web.hakija

import org.scalatra.swagger._
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import fi.vm.sade.hakurekisteri.web.rest.support.{
  ApiFormat,
  IncidentReportSwaggerModel,
  ModelResponseMessage,
  OldSwaggerSyntax
}
import fi.vm.sade.hakurekisteri.hakija.Hakuehto
import fi.vm.sade.hakurekisteri.hakija.representation.{JSONHakijat, XMLHakijat}
import org.scalatra.swagger.DataType.{ContainerDataType, ValueDataType}

trait HakijaSwaggerApi
    extends SwaggerSupport
    with IncidentReportSwaggerModel
    with OldSwaggerSyntax {

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
    ModelField("kaksoistutkinto", null, DataType.Boolean, required = false)
  )

  registerModel(Model("XMLHakutoive", "Hakutoive", hakutoiveFields.map { t => (t.name, t) }.toMap))

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
    ModelField(
      "hakutoiveet",
      null,
      ContainerDataType("List", Some(ValueDataType("XMLHakutoive", None, Some("XMLHakutoive"))))
    )
  )

  registerModel(Model("XMLHakemus", "Hakemus", hakemusFields.map { t => (t.name, t) }.toMap))

  val hakijaFields = Seq(
    ModelField("hetu", null, DataType.String),
    ModelField("oppijanumero", null, DataType.String),
    ModelField("sukunimi", null, DataType.String),
    ModelField("etunimet", null, DataType.String),
    ModelField("kutsumanimi", null, DataType.String, required = false),
    ModelField("lahiosoite", null, DataType.String),
    ModelField("postinumero", null, DataType.String),
    ModelField("postitoimipaikka", null, DataType.String),
    ModelField("maa", null, DataType.String),
    ModelField("kansalaisuus", null, DataType.String),
    ModelField("matkapuhelin", null, DataType.String, required = false),
    ModelField("sahkoposti", null, DataType.String, required = false),
    ModelField("kotikunta", null, DataType.String, required = false),
    ModelField("sukupuoli", null, DataType.String),
    ModelField("aidinkieli", null, DataType.String),
    ModelField("koulutusmarkkinointilupa", null, DataType.Boolean),
    ModelField("hakemus", null, ValueDataType("XMLHakemus", None, Some("XMLHakemus")))
  )

  registerModel(Model("XMLHakija", "Hakija", hakijaFields.map { t => (t.name, t) }.toMap))

  val hakijatFields = Seq(
    ModelField(
      "hakijat",
      null,
      ContainerDataType("List", Some(ValueDataType("XMLHakija", None, Some("XMLHakija"))))
    )
  )

  registerModel(Model("XMLHakijat", "Hakijat", hakijatFields.map { t => (t.name, t) }.toMap))

  registerModel(incidentReportModel)

  val query: OperationBuilder = apiOperation[XMLHakijat]("haeHakijat")
    .summary("näyttää kaikki hakijat")
    .description(
      "Näyttää listauksen hakeneista/valituista/paikan vastaanottaneista hakijoista parametrien mukaisesti."
    )
    .parameter(queryParam[Option[String]]("haku").description("haun oid").optional)
    .parameter(
      queryParam[Option[String]]("organisaatio")
        .description("koulutuksen tarjoajan tai sen yläorganisaation oid")
        .required
    )
    .parameter(queryParam[Option[String]]("hakukohdekoodi").description("hakukohdekoodi").optional)
    .parameter(
      queryParam[String]("hakuehto")
        .description("hakuehto")
        .allowableValues(Hakuehto.values.toList)
        .required
    )
    .parameter(
      queryParam[String]("tyyppi")
        .description("tietotyyppi")
        .allowableValues(ApiFormat.values.toList)
        .required
    )
    .parameter(
      queryParam[Option[Boolean]]("tiedosto")
        .description("palautetaanko vastaus tiedostona")
        .optional
    )
    .produces("application/json", "application/xml", "application/octet-stream")
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "internal server error"))
    .responseMessage(
      ModelResponseMessage(503, "hakemukset not yet loaded: utilise Retry-After response header")
    )
    .tags("hakijat")

  // V2 swagger

  val hakijatFieldsV2 = Seq(
    ModelField(
      "hakijat",
      null,
      ContainerDataType("List", Some(ValueDataType("JSONHakija", None, Some("JSONHakija"))))
    )
  )

  val hakijaFieldsV2 = Seq(
    ModelField("hetu", null, DataType.String),
    ModelField("oppijanumero", null, DataType.String),
    ModelField("sukunimi", null, DataType.String),
    ModelField("etunimet", null, DataType.String),
    ModelField("kutsumanimi", null, DataType.String, required = false),
    ModelField("lahiosoite", null, DataType.String),
    ModelField("postinumero", null, DataType.String),
    ModelField("postitoimipaikka", null, DataType.String),
    ModelField("maa", null, DataType.String),
    ModelField("kansalaisuus", null, DataType.String),
    ModelField("matkapuhelin", null, DataType.String, required = false),
    ModelField("sahkoposti", null, DataType.String, required = false),
    ModelField("kotikunta", null, DataType.String, required = false),
    ModelField("sukupuoli", null, DataType.String),
    ModelField("aidinkieli", null, DataType.String),
    ModelField("koulutusmarkkinointilupa", null, DataType.Boolean),
    ModelField("kiinnostunutoppisopimuksesta", null, DataType.Boolean),
    ModelField("huoltajannimi", null, DataType.String, required = false),
    ModelField("huoltajanpuhelinnumero", null, DataType.String, required = false),
    ModelField("huoltajansahkoposti", null, DataType.String, required = false),
    ModelField("hakemus", null, ValueDataType("XMLHakemus", None, Some("XMLHakemus"))),
    ModelField(
      "lisakysymykset",
      null,
      ValueDataType("JSONLisakysymys", None, Some("JSONLisakysymys"))
    )
  )

  val lisakysymysVastausFields = Seq(
    ModelField("vastausid", null, DataType.String),
    ModelField("vastausteksti", null, DataType.String)
  )
  val lisakysymysFields = Seq(
    ModelField("kysymysid", null, DataType.String),
    ModelField("kysymystyyppi", null, DataType.String),
    ModelField("kysymysteksti", null, DataType.String),
    ModelField(
      "vastaukset",
      null,
      ValueDataType("JSONLisakysymysVastaus", None, Some("JSONLisakysymysVastaus"))
    )
  )
  registerModel(Model("JSONHakija", "Hakija", hakijaFieldsV2.map { t => (t.name, t) }.toMap))
  registerModel(Model("JSONHakijat", "Hakijat", hakijatFieldsV2.map { t => (t.name, t) }.toMap))
  registerModel(
    Model("JSONLisakysymys", "Lisakysymys", lisakysymysFields.map { t => (t.name, t) }.toMap)
  )
  registerModel(
    Model(
      "JSONLisakysymysVastaus",
      "LisakysymysVastaus",
      lisakysymysVastausFields.map { t => (t.name, t) }.toMap
    )
  )

  val queryV2: OperationBuilder = apiOperation[JSONHakijat]("haeHakijat")
    .summary("näyttää kaikki hakijat")
    .description(
      "Näyttää listauksen hakeneista/valituista/paikan vastaanottaneista hakijoista parametrien mukaisesti."
    )
    .parameter(queryParam[Option[String]]("haku").description("haun oid").required)
    .parameter(
      queryParam[Option[String]]("organisaatio")
        .description("koulutuksen tarjoajan tai sen yläorganisaation oid")
        .optional
    )
    .parameter(queryParam[Option[String]]("hakukohdekoodi").description("hakukohdekoodi").optional)
    .parameter(
      queryParam[String]("hakuehto")
        .description("hakuehto")
        .allowableValues(Hakuehto.values.toList)
        .required
    )
    .parameter(
      queryParam[String]("tyyppi")
        .description("tietotyyppi")
        .allowableValues(ApiFormat.Excel, ApiFormat.Json)
        .required
    )
    .parameter(
      queryParam[Option[Boolean]]("tiedosto")
        .description("palautetaanko vastaus tiedostona")
        .optional
    )
    .produces("application/json", "application/octet-stream")
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "internal server error"))
    .responseMessage(
      ModelResponseMessage(503, "hakemukset not yet loaded: utilise Retry-After response header")
    )
    .tags("hakijat")

}
