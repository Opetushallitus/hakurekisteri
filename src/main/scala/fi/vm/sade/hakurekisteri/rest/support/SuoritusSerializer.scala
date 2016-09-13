package fi.vm.sade.hakurekisteri.rest.support

import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.{AmmatillisenKielikoeSuoritus, Suoritus, VapaamuotoinenSuoritus, VirallinenSuoritus}
import org.json4s.JsonAST._
import org.json4s.JsonDSL._
import org.json4s.{CustomSerializer, Extraction, Formats}

class SuoritusSerializer extends CustomSerializer[Suoritus]((format: Formats) => (
  {
    case suoritus:JObject  => ???
  },
  {

    case s: VapaamuotoinenSuoritus with Identified[_] =>
      implicit val frmts = format
      SuoritusSerializer.vapaamuotoinen(s) ~
      ("id" -> Extraction.decompose(s.id))

    case s: VapaamuotoinenSuoritus => SuoritusSerializer.vapaamuotoinen(s)

    case s: VirallinenSuoritus with Identified[_] =>
      implicit val frmts = format
      SuoritusSerializer.virallinen(s) ~
      ("id" -> Extraction.decompose(s.id))

    case s: VirallinenSuoritus =>
      implicit val frmts = format
      SuoritusSerializer.virallinen(s)

    case s: AmmatillisenKielikoeSuoritus with Identified[_] =>
      implicit val frmts = format
      SuoritusSerializer.ammatillisenKielikoe(s) ~
      ("id" -> Extraction.decompose(s.id))

    case s: AmmatillisenKielikoeSuoritus => SuoritusSerializer.ammatillisenKielikoe(s)
  }
  )

)


object SuoritusSerializer {

  def suoritus(s: Suoritus) = {
    ("henkiloOid" -> s.henkiloOid) ~
    ("source" -> s.source) ~
    ("vahvistettu" -> s.vahvistettu)
  }

  def virallinen(s: VirallinenSuoritus)(implicit format: Formats)  = {
    val rawSuoritus = suoritus(s) ~
      ("komo" -> s.komo) ~
      ("myontaja" -> s.myontaja) ~
      ("tila" -> s.tila) ~
      ("valmistuminen" -> Extraction.decompose(s.valmistuminen)) ~
      ("yksilollistaminen" -> Extraction.decompose(s.yksilollistaminen)) ~
      ("suoritusKieli" -> s.suoritusKieli)
    s.opiskeluoikeus match {

      case Some(oikeus) => rawSuoritus ~ ("opintoOikeus" -> oikeus.toString)
      case None => rawSuoritus
    }
  }


  def vapaamuotoinen(s: VapaamuotoinenSuoritus) = {
      suoritus(s) ~
      ("kuvaus" -> s.kuvaus) ~
      ("myontaja" -> s.myontaja) ~
      ("vuosi" -> s.vuosi) ~
      ("kkTutkinto" -> s.kkTutkinto)

  }

  def ammatillisenKielikoe(s: AmmatillisenKielikoeSuoritus) = {
      suoritus(s) ~
      ("kuvaus" -> s.kuvaus) ~
      ("myontaja" -> s.myontaja) ~
      ("vuosi" -> s.vuosi) ~
      ("tyyppi" -> AmmatillisenKielikoeSuoritus.tyyppi)
  }
}
