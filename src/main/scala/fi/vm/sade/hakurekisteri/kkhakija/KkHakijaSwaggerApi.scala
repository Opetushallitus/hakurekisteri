package fi.vm.sade.hakurekisteri.kkhakija

import fi.vm.sade.hakurekisteri.hakija.Hakuehto
import fi.vm.sade.hakurekisteri.integration.valintatulos.{Vastaanottotila, Valintatila}
import org.scalatra.swagger.AllowableValues.AnyValue
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import org.scalatra.swagger._
import fi.vm.sade.hakurekisteri.rest.support.{ApiFormat, OldSwaggerSyntax}

trait KkHakijaSwaggerApi extends SwaggerSupport with OldSwaggerSyntax {
  override protected val applicationName = Some("kkhakijat")

  val hakukohteenKoulutuksetFields = Seq(
    ModelField("komoOid", null, DataType.String, None, AnyValue, required = true),
    ModelField("tkKoulutuskoodi", null, DataType.String, None, AnyValue, required = true),
    ModelField("kkKoulutusId", null, DataType.String, None, AnyValue, required = false)
  )

  registerModel(Model("HakukohteenKoulutukset", "HakukohteenKoulutukset", hakukohteenKoulutuksetFields.map { t => (t.name, t) }.toMap))

  val ilmoittautuminenFields = Seq(
    ModelField("kausi", null, DataType.String, None, AnyValue, required = true),
    ModelField("tila", null, DataType.Int, None, AllowableValues(1 to 4), required = true)
  )

  registerModel(Model("Ilmoittautuminen", "Ilmoittautuminen", ilmoittautuminenFields.map{ t => (t.name, t) }.toMap))

  val hakemusFields = Seq(
    ModelField("haku", null, DataType.String, None, AnyValue, required = true),
    ModelField("hakuVuosi", null, DataType.Int, None, AnyValue, required = true),
    ModelField("hakuKausi", null, DataType.String, None, AllowableValues("K", "S"), required = true),
    ModelField("hakemusnumero", null, DataType.String, None, AnyValue, required = true),
    ModelField("organisaatio", null, DataType.String, None, AnyValue, required = true),
    ModelField("hakukohde", null, DataType.String, None, AnyValue, required = true),
    ModelField("hakukohdeKkId", null, DataType.String, None, AnyValue, required = false),
    ModelField("avoinVayla", null, DataType.Boolean, None, AnyValue, required = false),
    ModelField("valinnanTila", null, DataType.String, None, AllowableValues(Valintatila.values.map(_.toString)), required = false),
    ModelField("vastaanottotieto", null, DataType.String, None, AllowableValues(Vastaanottotila.values.map(_.toString)), required = false),
    ModelField("ilmoittautumiset", null, DataType.GenList(DataType("Ilmoittautuminen")), None, AnyValue, required = true),
    ModelField("pohjakoulutus", null, DataType.GenList(DataType.String), None, AllowableValues("yo", "am", "amt", "kk", "ulk", "avoin", "muu"), required = true),
    ModelField("julkaisulupa", null, DataType.Boolean, None, AnyValue, required = false),
    ModelField("hKelpoisuus", null, DataType.String, None, AllowableValues("NOT_CHECKED", "ELIGIBLE", "INADEQUATE", "INELIGIBLE"), required = true),
    ModelField("hKelpoisuusLahde", null, DataType.String, None, AllowableValues("UNKNOWN", "REGISTER", "ORIGINAL_DIPLOMA", "OFFICIALLY_AUTHENTICATED_COPY", "LEARNING_PROVIDER", "COPY", "AUTHENTICATED_COPY"), required = false),
    ModelField("hakukohteenKoulutukset", null, DataType.GenList(DataType("HakukohteenKoulutus")), None, AnyValue, required = true)
  )

  registerModel(Model("Hakemus", "Hakemus", hakemusFields.map{ t => (t.name, t) }.toMap))

  val hakijaFields = Seq(
    ModelField("hetu", null, DataType.String, None, AnyValue, required = true),
    ModelField("oppijanumero", null, DataType.String, None, AnyValue, required = true),
    ModelField("sukunimi", null, DataType.String, None, AnyValue, required = true),
    ModelField("etunimet", null, DataType.String, None, AnyValue, required = true),
    ModelField("kutsumanimi", null, DataType.String, None, AnyValue, required = false),
    ModelField("lahiosoite", null, DataType.String, None, AnyValue, required = true),
    ModelField("postinumero", null, DataType.String, None, AnyValue, required = true),
    ModelField("postitoimipaikka", null, DataType.String, None, AnyValue, required = true),
    ModelField("maa", null, DataType.String, None, AnyValue, required = true),
    ModelField("kansalaisuus", null, DataType.String, None, AnyValue, required = true),
    ModelField("matkapuhelin", null, DataType.String, None, AnyValue, required = false),
    ModelField("puhelin", null, DataType.String, None, AnyValue, required = false),
    ModelField("sahkoposti", null, DataType.String, None, AnyValue, required = false),
    ModelField("kotikunta", null, DataType.String, None, AnyValue, required = true),
    ModelField("sukupuoli", null, DataType.String, None, AllowableValues("0", "1", "2", "9"), required = false),
    ModelField("aidinkieli", null, DataType.String, None, AnyValue, required = true),
    ModelField("asiointikieli", null, DataType.String, None, AllowableValues("1", "2", "3", "9"), required = true),
    ModelField("koulusivistyskieli", null, DataType.String, None, AnyValue, required = true),
    ModelField("koulutusmarkkinointilupa", null, DataType.Boolean, None, AnyValue, required = false),
    ModelField("onYlioppilas", null, DataType.Boolean, None, AnyValue, required = true),
    ModelField("hakemukset", null, DataType.GenList(DataType("Hakemus")), None, AnyValue, required = true)
  )

  registerModel(Model("Hakija", "Hakija", hakijaFields.map{ t => (t.name, t) }.toMap))

  val query: OperationBuilder = apiOperation[Seq[Hakija]]("haeKkHakijat")
    .summary("näyttää kaikki hakijat")
    .notes("Näyttää listauksen hakeneista/valituista/paikan vastaanottaneista hakijoista parametrien mukaisesti.")
    .parameter(queryParam[Option[String]]("oppijanumero").description("henkilön oid / oppijanumero, pakollinen jos hakukohdetta ei ole määritetty").optional)
    .parameter(queryParam[Option[String]]("haku").description("haun oid").optional)
    .parameter(queryParam[Option[String]]("organisaatio").description("koulutuksen tarjoajan tai sen yläorganisaation oid").optional)
    .parameter(queryParam[Option[String]]("hakukohde").description("hakukohteen oid, pakollinen jos oppijanumeroa ei ole määritetty").optional)
    .parameter(queryParam[String]("hakuehto").description("hakuehto").allowableValues(Hakuehto.values.toList).required)
    .parameter(queryParam[String]("tyyppi").description("tyyppi").allowableValues(ApiFormat.Json, ApiFormat.Excel))

}
