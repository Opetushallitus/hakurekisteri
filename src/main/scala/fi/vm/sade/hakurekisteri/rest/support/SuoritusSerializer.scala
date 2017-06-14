package fi.vm.sade.hakurekisteri.rest.support


import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen.Yksilollistetty
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, VapaamuotoinenSuoritus, VirallinenSuoritus}
import org.joda.time.LocalDate
import org.json4s.JsonAST._
import org.json4s.JsonDSL._
import org.json4s.{CustomSerializer, DefaultFormats, Extraction, Formats}

class SuoritusSerializer extends CustomSerializer[Suoritus]((format: Formats) => (
  {
    case suoritus:JObject  =>
      implicit val localDateSerializer = new LocalDateSerializer()

      implicit val formats = DefaultFormats + localDateSerializer

      val JString(henkiloOid) = suoritus \ "henkiloOid"
      val JString(source) = suoritus \ "source"
      val JBool(vahvistettu) = suoritus \ "vahvistettu"
      val JString(komo) = suoritus \ "komo"
      val JString(myontaja) = suoritus \ "myontaja"
      val JString(tila) = suoritus \ "tila"
      val valmistuminen = (suoritus \ "valmistuminen").extract[LocalDate]
      val yksilollistaminen = (suoritus \ "yksilollistaminen").extract[Yksilollistetty]
      val JString(suorituskieli) = suoritus \ "suorituskieli"

      new VirallinenSuoritus(
        komo = komo,
        henkilo = henkiloOid,
        myontaja = myontaja,
        valmistuminen = valmistuminen,
        lahde = source,
        yksilollistaminen = yksilollistaminen,
        suoritusKieli = suorituskieli,
        vahv = vahvistettu,
        tila = tila
      )
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
}
