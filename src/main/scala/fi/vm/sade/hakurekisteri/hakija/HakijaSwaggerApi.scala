package fi.vm.sade.hakurekisteri.hakija

import org.scalatra.swagger._
import org.scalatra.swagger.AllowableValues.AnyValue
import scala.Some
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import fi.vm.sade.hakurekisteri.rest.support.{ApiFormat, OldSwaggerSyntax}

trait HakijaSwaggerApi extends SwaggerSupport with OldSwaggerSyntax {
  override protected val applicationName = Some("hakijat")

  val hakutoiveFields = Seq(ModelField("hakujno", null, DataType.Int, None, AnyValue, required = true),
    ModelField("oppilaitos", null, DataType.String, None, AnyValue, required = true),
    ModelField("opetuspiste", null, DataType.String, None, AnyValue, required = false),
    ModelField("opetuspisteennimi", null, DataType.String, None, AnyValue, required = false),
    ModelField("koulutus", null, DataType.String, None, AnyValue, required = true),
    ModelField("harkinnanvaraisuusperuste", null, DataType.String, None, AnyValue, required = false),
    ModelField("urheilijanammatillinenkoulutus", null, DataType.String, None, AnyValue, required = false),
    ModelField("yhteispisteet", null, DataType("double"), None, AnyValue, required = false),
    ModelField("valinta", null, DataType.String, None, AnyValue, required = false),
    ModelField("vastaanotto", null, DataType.String, None, AnyValue, required = false),
    ModelField("lasnaolo", null, DataType.String, None, AnyValue, required = false),
    ModelField("terveys", null, DataType.String, None, AnyValue, required = false),
    ModelField("aiempiperuminen", null, DataType.Boolean, None, AnyValue, required = false),
    ModelField("kaksoistutkinto", null, DataType.Boolean, None, AnyValue, required = false))

  registerModel(Model("XMLHakutoive", "Hakutoive", hakutoiveFields.map{ t => (t.name, t) }.toMap))

  val hakemusFields = Seq(ModelField("vuosi", null, DataType.String, None, AnyValue, required = true),
    ModelField("kausi", null, DataType.String, None, AnyValue, required = true),
    ModelField("hakemusnumero", null, DataType.String, None, AnyValue, required = true),
    ModelField("lahtokoulu", null, DataType.String, None, AnyValue, required = false),
    ModelField("lahtokoulunnimi", null, DataType.String, None, AnyValue, required = false),
    ModelField("luokka", null, DataType.String, None, AnyValue, required = false),
    ModelField("luokkataso", null, DataType.String, None, AnyValue, required = false),
    ModelField("pohjakoulutus", null, DataType.String, None, AnyValue, required = true),
    ModelField("todistusvuosi", null, DataType.String, None, AnyValue, required = false),
    ModelField("julkaisulupa", null, DataType.Boolean, None, AnyValue, required = false),
    ModelField("yhteisetaineet", null, DataType("double"), None, AnyValue, required = false),
    ModelField("lukiontasapisteet", null, DataType("double"), None, AnyValue, required = false),
    ModelField("lisapistekoulutus", null, DataType.String, None, AnyValue, required = false),
    ModelField("yleinenkoulumenestys", null, DataType("double"), None, AnyValue, required = false),
    ModelField("painotettavataineet", null, DataType("double"), None, AnyValue, required = false),
    ModelField("hakutoiveet", null, DataType.GenList(DataType("XMLHakutoive")), None, AnyValue, required = true))

  registerModel(Model("XMLHakemus", "Hakemus", hakemusFields.map{ t => (t.name, t) }.toMap))

  val hakijaFields = Seq(ModelField("hetu", null, DataType.String, None, AnyValue, required = true),
    ModelField("oppijanumero", null, DataType.String, None, AnyValue, required = true),
    ModelField("sukunimi", null, DataType.String, None, AnyValue, required = true),
    ModelField("etunimet", null, DataType.String, None, AnyValue, required = true),
    ModelField("kutsumanimi", null, DataType.String, None, AnyValue, required = false),
    ModelField("lahiosoite", null, DataType.String, None, AnyValue, required = true),
    ModelField("postinumero", null, DataType.String, None, AnyValue, required = true),
    ModelField("maa", null, DataType.String, None, AnyValue, required = true),
    ModelField("kansalaisuus", null, DataType.String, None, AnyValue, required = true),
    ModelField("matkapuhelin", null, DataType.String, None, AnyValue, required = false),
    ModelField("sahkoposti", null, DataType.String, None, AnyValue, required = false),
    ModelField("kotikunta", null, DataType.String, None, AnyValue, required = false),
    ModelField("sukupuoli", null, DataType.String, None, AnyValue, required = true),
    ModelField("aidinkieli", null, DataType.String, None, AnyValue, required = true),
    ModelField("koulutusmarkkinointilupa", null, DataType.Boolean, None, AnyValue, required = true),
    ModelField("hakemus", null, DataType("XMLHakemus"), None, AnyValue, required = true))

  registerModel(Model("XMLHakija", "Hakija", hakijaFields.map{ t => (t.name, t) }.toMap))

  val hakijatFields = Seq(ModelField("hakijat", null, DataType.GenList(DataType("XMLHakija")), None, AnyValue, required = true))

  registerModel(Model("XMLHakijat", "Hakijat", hakijatFields.map{ t => (t.name, t) }.toMap))

  val query: OperationBuilder = apiOperation[XMLHakijat]("haeHakijat")
    .summary("näyttää kaikki hakijat")
    .notes("Näyttää listauksen hakeneista/valituista/paikan vastaanottaneista hakijoista parametrien mukaisesti.")
    .parameter(queryParam[Option[String]]("haku").description("haun oid").optional)
    .parameter(queryParam[Option[String]]("organisaatio").description("koulutuksen tarjoajan tai sen yläorganisaation oid").optional)
    .parameter(queryParam[Option[String]]("hakukohdekoodi").description("hakukohdekoodi").optional)
    .parameter(queryParam[String]("hakuehto").description("hakuehto").allowableValues(Hakuehto.values.toList).required)
    .parameter(queryParam[String]("tyyppi").description("tietotyyppi").allowableValues(ApiFormat.values.toList).required)
    .parameter(queryParam[Option[Boolean]]("tiedosto").description("palautetaanko vastaus tiedostona").optional)
}
