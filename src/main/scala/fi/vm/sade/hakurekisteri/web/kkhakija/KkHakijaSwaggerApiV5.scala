package fi.vm.sade.hakurekisteri.web.kkhakija

import fi.vm.sade.hakurekisteri.hakija.Hakuehto
import fi.vm.sade.hakurekisteri.integration.valintatulos.{Valintatila, Vastaanottotila}
import fi.vm.sade.hakurekisteri.web.rest.support.{
  ApiFormat,
  IncidentReportSwaggerModel,
  ModelResponseMessage,
  OldSwaggerSyntax
}
import org.scalatra.swagger.DataType.{ContainerDataType, ValueDataType}
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import org.scalatra.swagger._

trait KkHakijaSwaggerApiV5
    extends SwaggerSupport
    with IncidentReportSwaggerModel
    with OldSwaggerSyntax {

  val hakukohteenKoulutusFields: Seq[ModelField] = Seq(
    ModelField("komoOid", null, DataType.String),
    ModelField("tkKoulutuskoodi", null, DataType.String),
    ModelField("kkKoulutusId", null, DataType.String, required = false),
    // TODO: tämä on vielä oikeasti {"arvo": "K"} -mallinen rakenne
    ModelField("koulutuksenAlkamiskausi", null, DataType.String, required = false),
    ModelField("koulutuksenAlkamisvuosi", null, DataType.String, required = false),
    ModelField("johtaaTutkintoon", null, DataType.String, required = false)
  )

  registerModel(
    Model(
      "HakukohteenKoulutus",
      "HakukohteenKoulutus",
      hakukohteenKoulutusFields.map { t => (t.name, t) }.toMap
    )
  )

  val ilmoittautuminenFields: Seq[ModelField] = Seq(
    ModelField("kausi", null, DataType.String),
    ModelField("tila", null, DataType.Int, None, AllowableValues(1 to 4), required = true)
  )

  registerModel(
    Model(
      "Ilmoittautuminen",
      "Ilmoittautuminen",
      ilmoittautuminenFields.map { t => (t.name, t) }.toMap
    )
  )

  val hyvaksymisenEhtoFields: Seq[ModelField] = Seq(
    ModelField("ehdollisestiHyvaksyttavissa", null, DataType.Boolean),
    ModelField("ehtoKoodi", null, DataType.String),
    ModelField("ehtoFI", null, DataType.String),
    ModelField("ehtoSV", null, DataType.String),
    ModelField("ehtoEN", null, DataType.String)
  )

  registerModel(
    Model(
      "HyvaksymisenEhto",
      "HyvaksymisenEhto",
      hyvaksymisenEhtoFields.map { t => (t.name, t) }.toMap
    )
  )

  val hakemusFields: Seq[ModelField] = Seq(
    ModelField("haku", null, DataType.String),
    ModelField("hakuVuosi", null, DataType.Int),
    ModelField(
      "hakuKausi",
      null,
      DataType.String,
      None,
      AllowableValues("K", "S"),
      required = true
    ),
    ModelField("hakemusnumero", null, DataType.String),
    ModelField("hakemusJattoAikaleima", null, DataType.String),
    ModelField("hakemusViimeinenMuokkausAikaleima", null, DataType.String),
    ModelField("organisaatio", null, DataType.String),
    ModelField("hakukohde", null, DataType.String),
    ModelField("hakutoivePrioriteetti", null, DataType.Int, required = false),
    ModelField("hakukohdeKkId", null, DataType.String, required = false),
    ModelField("avoinVayla", null, DataType.Boolean, required = false),
    ModelField(
      "valinnanTila",
      null,
      DataType.String,
      None,
      AllowableValues(Valintatila.values.map(_.toString)),
      required = false
    ),
    ModelField("valinnanAikaleima", null, DataType.String, required = false),
    ModelField("pisteet", null, DataType.Int, required = false),
    ModelField(
      "hyvaksymisenEhto",
      null,
      ValueDataType("HyvaksymisenEhto", None, Some("HyvaksymisenEhto")),
      required = false
    ),
    ModelField("valintatapajononTyyppi", null, DataType.String, None, required = false),
    ModelField("valintatapajononNimi", null, DataType.String, required = false),
    ModelField(
      "vastaanottotieto",
      null,
      DataType.String,
      None,
      AllowableValues(Vastaanottotila.values.map(_.toString)),
      required = false
    ),
    ModelField(
      "ilmoittautumiset",
      null,
      ContainerDataType(
        "List",
        Some(ValueDataType("Ilmoittautuminen", None, Some("Ilmoittautuminen")))
      )
    ),
    ModelField(
      "pohjakoulutus",
      null,
      DataType.GenList(DataType.String),
      None,
      AllowableValues("yo", "am", "amt", "kk", "ulk", "avoin", "muu")
    ),
    ModelField("julkaisulupa", null, DataType.Boolean, required = false),
    ModelField(
      "hKelpoisuus",
      null,
      DataType.String,
      Some(""),
      AllowableValues("NOT_CHECKED", "ELIGIBLE", "INADEQUATE", "INELIGIBLE")
    ),
    ModelField(
      "hKelpoisuusLahde",
      null,
      DataType.String,
      None,
      AllowableValues(
        "UNKNOWN",
        "REGISTER",
        "ORIGINAL_DIPLOMA",
        "OFFICIALLY_AUTHENTICATED_COPY",
        "LEARNING_PROVIDER",
        "COPY",
        "AUTHENTICATED_COPY"
      ),
      required = false
    ),
    ModelField(
      "hakukohteenKoulutukset",
      null,
      ContainerDataType(
        "List",
        Some(ValueDataType("HakukohteenKoulutus", None, Some("HakukohteenKoulutus")))
      )
    ),
    ModelField("hKelpoisuusMaksuvelvollisuus", null, DataType.String, required = false),
    ModelField("lukuvuosimaksu", null, DataType.String, required = false)
  )

  val liiteFields: Seq[ModelField] = Seq(
    ModelField("koulutusId", null, DataType.String),
    ModelField("tila", null, DataType.String),
    ModelField("saapumisenTila", null, DataType.String),
    ModelField("nimi", null, DataType.String),
    ModelField("vastaanottaja", null, DataType.String)
  )

  registerModel(Model("Hakemus", "Hakemus", hakemusFields.map { t => (t.name, t) }.toMap))

  registerModel(Model("Liite", "Liite", liiteFields.map { t => (t.name, t) }.toMap))

  val hakijaFields: Seq[ModelField] = Seq(
    ModelField("hetu", null, DataType.String),
    ModelField("syntymäaika", null, DataType.String),
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
    ModelField("puhelin", null, DataType.String, required = false),
    ModelField("sahkoposti", null, DataType.String, required = false),
    ModelField("kotikunta", null, DataType.String, defaultValue = Some("200")),
    ModelField(
      "sukupuoli",
      null,
      DataType.String,
      None,
      AllowableValues("0", "1", "2", "9"),
      required = false
    ),
    ModelField("aidinkieli", null, DataType.String),
    ModelField("asiointikieli", null, DataType.String, None, AllowableValues("1", "2", "3", "9")),
    ModelField("koulusivistyskielet", null, DataType.GenList(DataType.String), required = false),
    ModelField("koulutusmarkkinointilupa", null, DataType.Boolean, required = false),
    ModelField("onYlioppilas", null, DataType.Boolean),
    ModelField("yoSuoritusvuosi", null, DataType.String),
    ModelField("ensikertalainen", null, DataType.Boolean),
    ModelField("turvakielto", null, DataType.Boolean),
    ModelField(
      "hakemukset",
      null,
      ContainerDataType("List", Some(ValueDataType("Hakemus", None, Some("Hakemus"))))
    ),
    ModelField(
      "liitteet",
      null,
      ContainerDataType("List", Some(ValueDataType("Liite", None, Some("Liite"))))
    )
  )

  registerModel(Model("HakijaV5", "HakijaV5", hakijaFields.map { t => (t.name, t) }.toMap))

  registerModel(incidentReportModel)

  val query: OperationBuilder = apiOperation[Seq[HakijaV5]]("haeKkHakijat")
    .summary("näyttää kaikki hakijat")
    .description(
      "Näyttää listauksen hakeneista/valituista/paikan vastaanottaneista hakijoista parametrien mukaisesti."
    )
    .parameter(
      queryParam[Option[String]]("oppijanumero")
        .description("henkilön oid / oppijanumero, pakollinen jos hakukohdetta ei ole määritetty")
        .optional
    )
    .parameter(queryParam[Option[String]]("haku").description("haun oid").optional)
    .parameter(
      queryParam[Option[String]]("organisaatio")
        .description("koulutuksen tarjoajan tai sen yläorganisaation oid")
        .optional
    )
    .parameter(
      queryParam[Option[String]]("hakukohde")
        .description("hakukohteen oid, pakollinen jos oppijanumeroa ei ole määritetty")
        .optional
    )
    .parameter(
      queryParam[String]("hakuehto")
        .description(s"${Hakuehto.values.map(_.toString).reduce((prev, next) => s"$prev, $next")}")
        .allowableValues(Hakuehto.values.toList)
        .required
    )
    .parameter(
      queryParam[String]("tyyppi")
        .description(s"tietotyyppi ${ApiFormat.Excel} tai ${ApiFormat.Json}")
        .allowableValues(ApiFormat.Json, ApiFormat.Excel)
    )
    .parameter(
      queryParam[Option[Boolean]]("palautaKoulusivistyskielet")
        .description("palauta paikan vastaanottaneille koulusivistyskielet")
        .optional
    )
    .produces("application/json", "application/octet-stream")
    .responseMessage(
      ModelResponseMessage(400, "either parameter oppijanumero or hakukohde must be given")
    )
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(500, "error with tarjonta: [tarjonta exception message]"))
    .responseMessage(ModelResponseMessage(500, "error: [error description]"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "error in service"))
    .responseMessage(
      ModelResponseMessage(503, "hakemukset not yet loaded: utilise Retry-After response header")
    )
    .tags("kk-hakijat")

}
