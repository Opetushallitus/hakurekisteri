package fi.vm.sade.hakurekisteri.web.hakija

import fi.vm.sade.hakurekisteri.hakija.Hakuehto
import fi.vm.sade.hakurekisteri.hakija.representation.JSONHakijatV5
import fi.vm.sade.hakurekisteri.web.rest.support.{
  ApiFormat,
  IncidentReportSwaggerModel,
  ModelResponseMessage,
  OldSwaggerSyntax
}
import org.scalatra.swagger.DataType.{ContainerDataType, ValueDataType}
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import org.scalatra.swagger._

trait HakijaSwaggerApiV5
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

  val hakijaFieldsV5 = Seq(
    ModelField("hetu", null, DataType.String),
    ModelField("oppijanumero", null, DataType.String),
    ModelField("sukunimi", null, DataType.String),
    ModelField("etunimet", null, DataType.String),
    ModelField("kutsumanimi", null, DataType.String, required = false),
    ModelField("lahiosoite", null, DataType.String),
    ModelField("postinumero", null, DataType.String),
    ModelField("postitoimipaikka", null, DataType.String),
    ModelField("maa", null, DataType.String),
    ModelField("kansalaisuudet", null, DataType.GenList(DataType.String)),
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

  val hakijatFieldsV5 = Seq(
    ModelField(
      "hakijat",
      null,
      ContainerDataType("List", Some(ValueDataType("JSONHakijaV5", None, Some("JSONHakijaV5"))))
    )
  )

  registerModel(Model("JSONHakijatV5", "Hakijat", hakijatFieldsV5.map { t => (t.name, t) }.toMap))
  registerModel(Model("JSONHakijaV5", "Hakija", hakijaFieldsV5.map { t => (t.name, t) }.toMap))
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

  val queryV2: OperationBuilder = apiOperation[JSONHakijatV5]("haeHakijat")
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

