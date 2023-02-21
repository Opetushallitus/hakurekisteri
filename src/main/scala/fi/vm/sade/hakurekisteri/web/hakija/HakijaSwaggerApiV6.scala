package fi.vm.sade.hakurekisteri.web.hakija

import fi.vm.sade.hakurekisteri.hakija.Hakuehto
import fi.vm.sade.hakurekisteri.hakija.representation.JSONHakijatV6
import fi.vm.sade.hakurekisteri.web.rest.support.{
  ApiFormat,
  IncidentReportSwaggerModel,
  ModelResponseMessage,
  OldSwaggerSyntax
}
import org.scalatra.swagger.DataType.{ContainerDataType, ValueDataType}
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import org.scalatra.swagger._

trait HakijaSwaggerApiV6
    extends SwaggerSupport
    with IncidentReportSwaggerModel
    with OldSwaggerSyntax {

  val hakutoiveFields = Seq(
    ModelField("hakujno", null, DataType.Int),
    ModelField("oppilaitos", null, DataType.String),
    ModelField("opetuspiste", null, DataType.String, required = false),
    ModelField("opetuspisteennimi", null, DataType.String, required = false),
    ModelField("hakukohdeOid", null, DataType.String),
    ModelField("koulutus", null, DataType.String),
    ModelField("harkinnanvaraisuusperuste", null, DataType.String, required = false),
    ModelField("urheilijanammatillinenkoulutus", null, DataType.String, required = false),
    ModelField("yhteispisteet", null, DataType.Double, required = false),
    ModelField("valinta", null, DataType.String, required = false),
    ModelField("vastaanotto", null, DataType.String, required = false),
    ModelField("lasnaolo", null, DataType.String, required = false),
    ModelField("terveys", null, DataType.String, required = false),
    ModelField("aiempiperuminen", null, DataType.Boolean, required = false),
    ModelField("kaksoistutkinto", null, DataType.Boolean, required = false),
    ModelField("koulutuksenKieli", null, DataType.String, required = false),
    ModelField("keskiarvo", null, DataType.String, required = false)
  )

  registerModel(Model("HakutoiveV6", "Hakutoive", hakutoiveFields.map { t => (t.name, t) }.toMap))

  val osaaminenFields = Seq(
    ModelField("yleinen_kielitutkinto_fi", null, DataType.String, required = false),
    ModelField("valtionhallinnon_kielitutkinto_fi", null, DataType.String, required = false),
    ModelField("yleinen_kielitutkinto_sv", null, DataType.String, required = false),
    ModelField("valtionhallinnon_kielitutkinto_sv", null, DataType.String, required = false),
    ModelField("yleinen_kielitutkinto_en", null, DataType.String, required = false),
    ModelField("valtionhallinnon_kielitutkinto_en", null, DataType.String, required = false),
    ModelField("yleinen_kielitutkinto_se", null, DataType.String, required = false),
    ModelField("valtionhallinnon_kielitutkinto_se", null, DataType.String, required = false)
  )

  registerModel(Model("OsaaminenV6", "Osaaminen", osaaminenFields.map { t => (t.name, t) }.toMap))

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
    ModelField("muukoulutus", null, DataType.String, required = false),
    ModelField("julkaisulupa", null, DataType.Boolean, required = false),
    ModelField("yhteisetaineet", null, DataType.Double, required = false),
    ModelField("lukiontasapisteet", null, DataType.Double, required = false),
    ModelField("lisapistekoulutus", null, DataType.String, required = false),
    ModelField("yleinenkoulumenestys", null, DataType.Double, required = false),
    ModelField("painotettavataineet", null, DataType.Double, required = false),
    ModelField(
      "hakutoiveet",
      null,
      ContainerDataType("List", Some(ValueDataType("HakutoiveV6", None, Some("HakutoiveV6"))))
    ),
    ModelField("osaaminen", null, ValueDataType("OsaaminenV6", None, Some("OsaaminenV6")))
  )

  registerModel(Model("HakijaV6Hakemus", "Hakemus", hakemusFields.map { t => (t.name, t) }.toMap))

  val huoltajaFields = Seq(
    ModelField("nimi", null, DataType.String, required = false),
    ModelField("puhelinnumero", null, DataType.String, required = false),
    ModelField("sahkoposti", null, DataType.String, required = false)
  )

  val hakijaFieldsV6 = Seq(
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
    ModelField("muupuhelin", null, DataType.String, required = false),
    ModelField("sahkoposti", null, DataType.String, required = false),
    ModelField("kotikunta", null, DataType.String, required = false),
    ModelField("sukupuoli", null, DataType.String),
    ModelField("aidinkieli", null, DataType.String),
    ModelField("opetuskieli", null, DataType.String),
    ModelField("koulutusmarkkinointilupa", null, DataType.Boolean),
    ModelField("kiinnostunutoppisopimuksesta", null, DataType.Boolean),
    ModelField("huoltaja1", null, ValueDataType("JSONHuoltaja", None, Some("JSONHuoltaja"))),
    ModelField("huoltaja2", null, ValueDataType("JSONHuoltaja", None, Some("JSONHuoltaja"))),
    ModelField(
      "oppivelvollisuusVoimassaAsti",
      "Päivämäärä muotoa YYYY-MM-DD",
      DataType.String,
      required = false
    ),
    ModelField(
      "oikeusMaksuttomaanKoulutukseenVoimassaAsti",
      "Päivämäärä muotoa YYYY-MM-DD",
      DataType.String,
      required = false
    ),
    ModelField("hakemus", null, ValueDataType("HakijaV6Hakemus", None, Some("HakijaV6Hakemus"))),
    ModelField(
      "lisakysymykset",
      null,
      ValueDataType("JSONLisakysymysV6", None, Some("JSONLisakysymysV6"))
    )
  )

  val lisakysymysVastausFields = Seq(
    ModelField("vastausid", null, DataType.String),
    ModelField("vastausteksti", null, DataType.String)
  )
  val lisakysymysFields = Seq(
    ModelField("kysymysid", null, DataType.String),
    ModelField("hakukohdeOids", null, DataType.GenList(DataType.String)),
    ModelField("kysymystyyppi", null, DataType.String),
    ModelField("kysymysteksti", null, DataType.String),
    ModelField(
      "vastaukset",
      null,
      ValueDataType("JSONLisakysymysVastausV6", None, Some("JSONLisakysymysVastausV6"))
    )
  )

  val hakijatFieldsV6 = Seq(
    ModelField(
      "hakijat",
      null,
      ContainerDataType("List", Some(ValueDataType("JSONHakijaV6", None, Some("JSONHakijaV6"))))
    )
  )

  registerModel(Model("JSONHuoltaja", "Huoltaja", huoltajaFields.map { t => (t.name, t) }.toMap))
  registerModel(Model("JSONHakijatV6", "Hakijat", hakijatFieldsV6.map { t => (t.name, t) }.toMap))
  registerModel(Model("JSONHakijaV6", "Hakija", hakijaFieldsV6.map { t => (t.name, t) }.toMap))
  registerModel(
    Model("JSONLisakysymysV6", "Lisakysymys", lisakysymysFields.map { t => (t.name, t) }.toMap)
  )
  registerModel(
    Model(
      "JSONLisakysymysVastausV6",
      "LisakysymysVastaus",
      lisakysymysVastausFields.map { t => (t.name, t) }.toMap
    )
  )

  val queryV6: OperationBuilder = apiOperation[JSONHakijatV6]("haeHakijatV6")
    .summary("näyttää kaikki hakijat")
    .description(
      "Näyttää listauksen hakeneista/valituista/paikan vastaanottaneista hakijoista parametrien mukaisesti."
    )
    .parameter(queryParam[Option[String]]("haku").description("haun oid").required)
    .parameter(
      queryParam[Option[String]]("organisaatio")
        .description("koulutuksen tarjoajan tai sen yläorganisaation oid")
        .required
    )
    .parameter(queryParam[Option[String]]("hakukohdekoodi").description("hakukohdekoodi").optional)
    .parameter(queryParam[Option[String]]("hakukohdeoid").description("hakukohdeoid").optional)
    .parameter(
      queryParam[String]("hakuehto")
        .description(s"${Hakuehto.values.map(_.toString).reduce((prev, next) => s"$prev, $next")}")
        .allowableValues(Hakuehto.values.toList)
        .required
    )
    .parameter(
      queryParam[String]("tyyppi")
        .description(s"tietotyyppi ${ApiFormat.Excel} tai ${ApiFormat.Json}")
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
