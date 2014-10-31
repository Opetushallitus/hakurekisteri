package fi.vm.sade.hakurekisteri.kkhakija

import fi.vm.sade.hakurekisteri.hakija.Hakuehto
import fi.vm.sade.hakurekisteri.integration.valintatulos.{Vastaanottotila, Valintatila}
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import org.scalatra.swagger._
import fi.vm.sade.hakurekisteri.rest.support.{ApiFormat, OldSwaggerSyntax}

trait KkHakijaSwaggerApi extends SwaggerSupport with OldSwaggerSyntax {

  val hakukohteenKoulutusFields = Seq(
    ModelField("komoOid", null, DataType.String),
    ModelField("tkKoulutuskoodi", null, DataType.String),
    ModelField("kkKoulutusId", null, DataType.String, required = false)
  )

  registerModel(Model("HakukohteenKoulutus", "HakukohteenKoulutus", hakukohteenKoulutusFields.map { t => (t.name, t) }.toMap))

  val ilmoittautuminenFields = Seq(
    ModelField("kausi", null, DataType.String),
    ModelField("tila", null, DataType.Int, None, AllowableValues(1 to 4), required = true)
  )

  registerModel(Model("Ilmoittautuminen", "Ilmoittautuminen", ilmoittautuminenFields.map{ t => (t.name, t) }.toMap))

  val hakemusFields = Seq(
    ModelField("haku", null, DataType.String),
    ModelField("hakuVuosi", null, DataType.Int),
    ModelField("hakuKausi", null, DataType.String, None, AllowableValues("K", "S"), required = true),
    ModelField("hakemusnumero", null, DataType.String),
    ModelField("organisaatio", null, DataType.String),
    ModelField("hakukohde", null, DataType.String),
    ModelField("hakukohdeKkId", null, DataType.String, required = false),
    ModelField("avoinVayla", null, DataType.Boolean, required = false),
    ModelField("valinnanTila", null, DataType.String, None, AllowableValues(Valintatila.values.map(_.toString)), required = false),
    ModelField("vastaanottotieto", null, DataType.String, None, AllowableValues(Vastaanottotila.values.map(_.toString)), required = false),
    ModelField("ilmoittautumiset", null, DataType.GenList(DataType("Ilmoittautuminen"))),
    ModelField("pohjakoulutus", null, DataType.GenList(DataType.String), None, AllowableValues("yo", "am", "amt", "kk", "ulk", "avoin", "muu")),
    ModelField("julkaisulupa", null, DataType.Boolean, required = false),
    ModelField("hKelpoisuus", null, DataType.String, None, AllowableValues("NOT_CHECKED", "ELIGIBLE", "INADEQUATE", "INELIGIBLE")),
    ModelField("hKelpoisuusLahde", null, DataType.String, None, AllowableValues("UNKNOWN", "REGISTER", "ORIGINAL_DIPLOMA", "OFFICIALLY_AUTHENTICATED_COPY", "LEARNING_PROVIDER", "COPY", "AUTHENTICATED_COPY"), required = false),
    ModelField("hakukohteenKoulutukset", null, DataType.GenList(DataType("HakukohteenKoulutus")))
  )

  registerModel(Model("Hakemus", "Hakemus", hakemusFields.map{ t => (t.name, t) }.toMap))

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
    ModelField("puhelin", null, DataType.String, required = false),
    ModelField("sahkoposti", null, DataType.String, required = false),
    ModelField("kotikunta", null, DataType.String),
    ModelField("sukupuoli", null, DataType.String, None, AllowableValues("0", "1", "2", "9"), required = false),
    ModelField("aidinkieli", null, DataType.String),
    ModelField("asiointikieli", null, DataType.String, None, AllowableValues("1", "2", "3", "9")),
    ModelField("koulusivistyskieli", null, DataType.String),
    ModelField("koulutusmarkkinointilupa", null, DataType.Boolean, required = false),
    ModelField("onYlioppilas", null, DataType.Boolean),
    ModelField("hakemukset", null, DataType.GenList(DataType("Hakemus")))
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
    .produces("application/json", "application/octet-stream")
    .responseMessage(StringResponseMessage(400, "either parameter oppijanumero or hakukohde must be given"))
    .responseMessage(StringResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(StringResponseMessage(500, "error with tarjonta: [tarjonta exception message]"))
    .responseMessage(StringResponseMessage(500, "error: [error description]"))
    .responseMessage(StringResponseMessage(500, "back-end service timed out"))
    .responseMessage(StringResponseMessage(500, "error in service"))

}
