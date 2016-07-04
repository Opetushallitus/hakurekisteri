package fi.vm.sade.hakurekisteri.rest.support

import org.json4s.{Extraction, Formats, CustomSerializer}
import org.json4s.JsonAST._
import fi.vm.sade.hakurekisteri.suoritus.{VirallinenSuoritus, VapaamuotoinenSuoritus, Suoritus}
import org.json4s.JsonAST.JString
import org.json4s.JsonAST.JInt
import org.json4s.JsonDSL._
import org.joda.time.LocalDate
import java.util.UUID
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen.Yksilollistetty
import scala.util.Try
import fi.vm.sade.hakurekisteri.storage.Identified

class SuoritusSerializer extends CustomSerializer[Suoritus]((format: Formats) => (
  {
    case suoritus:JObject  =>
      implicit val form = format
      val rawSuoritus = SuoritusSerializer.extractSuoritus(suoritus)
      suoritus \ "id" match {
        case JNothing =>  rawSuoritus
        case jv: JValue =>  rawSuoritus.identify(jv.extract[UUID])
      }



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

  def extractSuoritus(suoritus: JValue)(implicit format: Formats): Suoritus  = {
    val JString(henkilo) = suoritus \ "henkiloOid"
    val JString(myontaja) = suoritus \ "myontaja"
    val JString(lahde) = suoritus \ "source"
    suoritus \ "kuvaus" match {
      case JString(kuvaus) =>
        val JInt(vuosi) = suoritus \ "vuosi"
        val JString(tyyppi) = suoritus \ "tyyppi"
        val JInt(index) = suoritus \ "index"
        VapaamuotoinenSuoritus(henkilo: String, kuvaus: String, myontaja: String, vuosi.toInt: Int, tyyppi: String, index.toInt: Int, lahde: String)
      case JNothing =>
        val JString(komo) = suoritus \ "komo"
        val JString(tila) = suoritus \ "tila"
        val valmistuminen: LocalDate = (suoritus \ "valmistuminen").extract[LocalDate]
        val yksilollistaminen = (suoritus \ "yksilollistaminen").extract[Yksilollistetty]
        val JString(suoritusKieli) = suoritus \ "suoritusKieli"
        val opiskeluoikeus = suoritus \ "opiskeluoikeus" match {
          case jv: JString => Try(jv.extract[UUID]).toOption
          case _ => None
        }
        val vahv = suoritus \ "vahvistettu" match {
          case JBool(value) => value
          case _ => false
        }
        VirallinenSuoritus(komo: String,
          myontaja: String,
          tila: String,
          valmistuminen: LocalDate,
          henkilo: String,
          yksilollistaminen: Yksilollistetty,
          suoritusKieli: String,
          opiskeluoikeus: Option[UUID],
          vahv: Boolean,
          lahde: String)
      case _ => throw new IllegalArgumentException("unable to extract Suoritus kuvaus is of wrong type")
    }
  }
}
