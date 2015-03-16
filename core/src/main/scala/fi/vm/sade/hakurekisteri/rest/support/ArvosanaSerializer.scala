package fi.vm.sade.hakurekisteri.rest.support

import java.util.UUID

import fi.vm.sade.hakurekisteri.arvosana.{Arvio, Arvosana}
import fi.vm.sade.hakurekisteri.storage.Identified
import org.joda.time.LocalDate
import org.json4s.{Extraction, CustomSerializer}
import org.json4s.JsonAST._
import org.json4s.JsonDSL._

class ArvosanaSerializer extends CustomSerializer[Arvosana](format => (
  {
    case arvosana: JObject  =>
      val id: Option[UUID] = arvosana.findField(jf => jf._1 == "id").map(_._2).collect { case JString(i) => UUID.fromString(i) }
      val JString(suoritus) = arvosana \ "suoritus"
      val arvio = arvosana \ "arvio"
      val JString(aine) = arvosana \ "aine"
      val lisatieto: Option[String] = arvosana.findField(_ == "lisatieto").map(_._2).collect {
        case JString(v) => v
      }
      val valinnainen: Boolean = arvosana \ "valinnainen" match {
        case JBool(v) => v
        case _ => false
      }

      implicit val formats = HakurekisteriJsonSupport.format
      val arv = Extraction.extract[Arvio](arvio)
      val myonnetty = arvosana \ "myonnetty" match {
        case JNothing => None
        case v: JValue => Some(Extraction.extract[LocalDate](v))
      }
      val JString(source) = arvosana \ "source"
      val jarjestys: Option[Int] = arvosana \ "jarjestys" match {
        case JNothing => None
        case JInt(i) => Some(i.toInt)
      }

      Arvosana(UUID.fromString(suoritus), arv, aine, lisatieto, valinnainen, myonnetty, source, jarjestys)
  },
  {
    case arvosana: Arvosana with Identified[UUID] =>
      val id = arvosana.id
      val suoritus = arvosana.suoritus

      implicit val formats = HakurekisteriJsonSupport.format
      val arvio = Extraction.decompose(arvosana.arvio)

      val aine: String = arvosana.aine
      val lisatieto: Option[String] = arvosana.lisatieto
      val valinnainen: Boolean = arvosana.valinnainen
      val myonnetty: Option[LocalDate] = arvosana.myonnetty
      val source: String = arvosana.source
      val jarjestys: Option[Int] = arvosana.jarjestys

      val core = ("id" -> id.toString) ~
        ("suoritus" -> suoritus.toString) ~
        ("arvio" -> arvio) ~
        ("aine" -> aine) ~
        ("source" -> source) ~
        ("valinnainen" -> valinnainen)

      val withLisatieto = lisatieto.map(l => core ~ ("lisatieto" -> l)).getOrElse(core)
      val withMyonnetty = myonnetty.map(m => withLisatieto ~ ("myonnetty" -> Extraction.decompose(m))).getOrElse(withLisatieto)
      val withJarjestys = jarjestys.map(j => withMyonnetty ~ ("jarjestys" -> j)).getOrElse(withMyonnetty)

      withJarjestys
  }
  )
)

