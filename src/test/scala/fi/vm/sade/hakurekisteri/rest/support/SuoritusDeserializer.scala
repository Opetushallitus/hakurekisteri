package fi.vm.sade.hakurekisteri.rest.support

import java.util.UUID

import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen.Yksilollistetty
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, VapaamuotoinenSuoritus, VirallinenSuoritus}
import org.joda.time.LocalDate
import org.json4s.JsonAST.{JInt, JString, _}
import org.json4s.{CustomSerializer, Formats}

import scala.util.Try

class SuoritusDeserializer
    extends CustomSerializer[Suoritus]((format: Formats) =>
      (
        { case suoritus: JObject =>
          implicit val form = format
          val rawSuoritus = SuoritusDeserializer.extractSuoritus(suoritus)
          suoritus \ "id" match {
            case JNothing   => rawSuoritus
            case jv: JValue => rawSuoritus.identify(jv.extract[UUID])
          }

        }, {
          implicit val form = format
          new SuoritusSerializer().serialize
        }
      )
    )

object SuoritusDeserializer {
  def extractSuoritus(suoritus: JValue)(implicit format: Formats): Suoritus = {
    val JString(henkilo) = suoritus \ "henkiloOid"
    val JString(myontaja) = suoritus \ "myontaja"
    val JString(lahde) = suoritus \ "source"
    suoritus \ "kuvaus" match {
      case JString(kuvaus) =>
        val JInt(vuosi) = suoritus \ "vuosi"
        val JString(tyyppi) = suoritus \ "tyyppi"
        val JInt(index) = suoritus \ "index"
        VapaamuotoinenSuoritus(
          henkilo: String,
          kuvaus: String,
          myontaja: String,
          vuosi.toInt: Int,
          tyyppi: String,
          index.toInt: Int,
          lahde: String
        )
      case JNothing =>
        val JString(komo) = suoritus \ "komo"
        val JString(tila) = suoritus \ "tila"
        val valmistuminen: LocalDate = (suoritus \ "valmistuminen").extract[LocalDate]
        val yksilollistaminen = (suoritus \ "yksilollistaminen").extract[Yksilollistetty]
        val JString(suoritusKieli) = suoritus \ "suoritusKieli"
        val opiskeluoikeus = suoritus \ "opiskeluoikeus" match {
          case jv: JString => Try(jv.extract[UUID]).toOption
          case _           => None
        }
        val vahv = suoritus \ "vahvistettu" match {
          case JBool(value) => value
          case _            => false
        }
        VirallinenSuoritus(
          komo: String,
          myontaja: String,
          tila: String,
          valmistuminen: LocalDate,
          henkilo: String,
          yksilollistaminen: Yksilollistetty,
          suoritusKieli: String,
          opiskeluoikeus: Option[UUID],
          vahv: Boolean,
          lahde: String
        )
      case _ =>
        throw new IllegalArgumentException("unable to extract Suoritus kuvaus is of wrong type")
    }
  }
}
