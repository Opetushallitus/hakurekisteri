package fi.vm.sade.hakurekisteri.web.rekisteritiedot

import org.scalatra.swagger.SwaggerSupport
import fi.vm.sade.hakurekisteri.web.arvosana.ArvosanaSwaggerModel
import fi.vm.sade.hakurekisteri.web.opiskelija.OpiskelijaSwaggerModel
import fi.vm.sade.hakurekisteri.web.opiskeluoikeus.OpiskeluoikeusSwaggerModel
import fi.vm.sade.hakurekisteri.web.suoritus.SuoritusSwaggerModel
import fi.vm.sade.hakurekisteri.web.rest.support.{IncidentReportSwaggerModel, ModelResponseMessage}
import fi.vm.sade.hakurekisteri.oppija.Oppija
import fi.vm.sade.hakurekisteri.web.oppija.{OppijaSwaggerModel, OppijatPostSize}

trait RekisteritiedotSwaggerApi extends SwaggerSupport with OppijaSwaggerModel with ArvosanaSwaggerModel with SuoritusSwaggerModel with OpiskelijaSwaggerModel with OpiskeluoikeusSwaggerModel with IncidentReportSwaggerModel {
  registerModel(arvioModel)
  registerModel(lahdeArvotModel)
  registerModel(arvosanaModel)
  registerModel(virallinenSuoritusModel)
  registerModel(vapaamuotoinenSuoritusModel)
  registerModel(opiskelijaModel)
  registerModel(opiskeluoikeusModel)
  registerModel(todistusModel)
  registerModel(oppijaModel)

  val query = apiOperation[Seq[Oppija]]("haeOppijat")
    .summary("Näyttää oppijoiden tiedot")
    .description("Näyttää listauksen oppijoiden tiedoista parametrien mukaisesti. Tämän resurssin oppijoiden Opiskeluoikeudet ovat aina tyhjiä listoja")
    .parameter(queryParam[Option[String]]("oppilaitosOid").description("koulutuksen tarjoajan  oid").optional)
    .parameter(queryParam[Option[String]]("vuosi").description("vuosi jona ollut kirjoilla oppilaitoksessa tai suorittanut suorituksen").optional)
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "error in service"))
    .tags("rekisteritiedot")

  val queryPost = apiOperation[Seq[Oppija]]("haeOppijatOppijanumeroidenPerusteella")
    .summary("Näyttää oppijoiden tiedot")
    .description("Näyttää listauksen oppijoiden tiedoista parametrien mukaisesti. Vastaava tietorakenne, kuin oppijat-rajapinnassa ilman ensikertalaisuus-tietoja.")
    .parameter(bodyParam[String]("oppijanumerot")
      .description(s"""lista oppijanumeroista (max ${OppijatPostSize.maxOppijatPostSize} kpl), esim ["1.2.246.562.24.00000000001", "1.2.246.562.24.00000000002"]""").required)
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "error in service"))
    .tags("rekisteritiedot")

  val read = apiOperation[Oppija]("haeOppija")
    .summary("Näyttää yhden oppijan tiedot")
    .description("Näyttää yhden oppijan tiedot oppijanumeron perusteella.")
    .parameter(pathParam[String]("oid").description("oppijanumero").required)
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "error in service"))
    .tags("rekisteritiedot")

  val light = apiOperation[Seq[LightWeightTiedot]]("haeOppijatKevyt")
    .summary("Näyttää kevennetyn version opiskelijatiedoista")
    .description("Näyttää nimen luokan ja onko henkilön arvosanoissa puutteita")
    .parameter(queryParam[Option[String]]("oppilaitosOid").description("koulutuksen tarjoajan  oid").optional)
    .parameter(queryParam[Option[String]]("vuosi").description("vuosi jona ollut kirjoilla oppilaitoksessa tai suorittanut suorituksen").optional)
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "error in service"))
    .tags("rekisteritiedot")

}
