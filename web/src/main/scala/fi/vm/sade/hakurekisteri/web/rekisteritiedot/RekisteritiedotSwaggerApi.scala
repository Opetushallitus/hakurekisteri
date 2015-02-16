package fi.vm.sade.hakurekisteri.web.rekisteritiedot

import org.scalatra.swagger.{DataType, SwaggerSupport}
import fi.vm.sade.hakurekisteri.web.arvosana.ArvosanaSwaggerModel
import fi.vm.sade.hakurekisteri.web.opiskelija.OpiskelijaSwaggerModel
import fi.vm.sade.hakurekisteri.web.opiskeluoikeus.OpiskeluoikeusSwaggerModel
import fi.vm.sade.hakurekisteri.web.suoritus.SuoritusSwaggerModel
import fi.vm.sade.hakurekisteri.web.rest.support.{ModelResponseMessage, IncidentReportSwaggerModel}
import fi.vm.sade.hakurekisteri.oppija.Oppija
import fi.vm.sade.hakurekisteri.web.oppija.OppijaSwaggerModel

trait RekisteritiedotSwaggerApi extends SwaggerSupport with OppijaSwaggerModel with ArvosanaSwaggerModel with SuoritusSwaggerModel with OpiskelijaSwaggerModel with OpiskeluoikeusSwaggerModel with IncidentReportSwaggerModel {



  registerModel(arvioModel)
  registerModel(arvosanaModel)
  //registerModel(suoritusModel)
  registerModel(virallinenSuoritusModel)
  registerModel(vapaamuotoinenSuoritusModel)
  registerModel(opiskelijaModel)
  registerModel(opiskeluoikeusModel)
  registerModel(todistusModel)
  registerModel(oppijaModel)


  val query = apiOperation[Seq[Oppija]]("haeOppijat")
    .summary("näyttää oppijoiden tiedot")
    .notes("Näyttää listauksen oppijoiden tiedoista parametrien mukaisesti. Tämän resurssin oppijoiden Opiskeluoikeudet ovat aina tyhjiä listoja")
    .parameter(queryParam[Option[String]]("oppilaitosOid").description("koulutuksen tarjoajan  oid").optional)
    .parameter(queryParam[Option[String]]("vuosi").description("vuosi jona ollut kirjoilla oppilaitoksessa tai suorittanut suorituksen").optional)
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "error in service"))

  val read = apiOperation[Oppija]("haeOppija")
    .summary("näyttää yhden oppijan tiedot")
    .notes("Näyttää yhden oppijan tiedot oppijanumeron perusteella.")
    .parameter(pathParam[String]("oid").description("oppijanumero").required)
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "error in service"))

}
