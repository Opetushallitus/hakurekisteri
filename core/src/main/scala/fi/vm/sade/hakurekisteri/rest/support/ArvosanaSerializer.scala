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
      val koetunnus: Option[String] = arvosana.findField(_._1 == "koetunnus").map(_._2).collect { case JString(v) => v }
      val JString(suoritus) = arvosana \ "suoritus"
      val arvio = arvosana \ "arvio"
      val JString(aine) = arvosana \ "aine"
      val aineyhdistelmarooli: Option[String] = arvosana.findField(_._1 == "aineyhdistelmarooli").map(_._2).collect { case JString(v) => v }
      val lisatieto: Option[String] = arvosana.findField(_._1 == "lisatieto").map(_._2).collect { case JString(v) => v }
      val valinnainen: Boolean = arvosana.findField(jf => jf._1 == "valinnainen").map(_._2) match {
        case Some(JBool(v)) => v
        case _ => false
      }

      implicit val formats = HakurekisteriJsonSupport.format
      val arv = Extraction.extract[Arvio](arvio)
      val myonnetty = arvosana.findField(_._1 == "myonnetty").map(_._2).collect { case JString(v) => Extraction.extract[LocalDate](v) }
      val JString(source) = arvosana \ "source"
      val jarjestys: Option[Int] = arvosana.findField(_._1 == "jarjestys").map(_._2).collect { case JInt(v) => v.toInt }

      Arvosana(UUID.fromString(suoritus), koetunnus, arv, aine, aineyhdistelmarooli, lisatieto, valinnainen, myonnetty, source, jarjestys)
  },
  {
    case arvosana: Arvosana with Identified[UUID @unchecked] =>
      val id = arvosana.id
      val suoritus = arvosana.suoritus

      implicit val formats = HakurekisteriJsonSupport.format
      val arvio = Extraction.decompose(arvosana.arvio)

      val aine: String = arvosana.aine
      val koetunnus: Option[String] = arvosana.koetunnus
      val aineyhdistelmarooli: Option[String] = arvosana.aineyhdistelmarooli
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

      val withKoetunnus = koetunnus.map(k => core ~ ("koetunnus" -> k)).getOrElse(core)
      val withAineyhdistelmarooli = aineyhdistelmarooli.map(a => withKoetunnus ~ ("aineyhdistelmarooli" -> a)).getOrElse(withKoetunnus)
      val withLisatieto = lisatieto.map(l => withAineyhdistelmarooli ~ ("lisatieto" -> l)).getOrElse(withAineyhdistelmarooli)
      val withMyonnetty = myonnetty.map(m => withLisatieto ~ ("myonnetty" -> Extraction.decompose(m))).getOrElse(withLisatieto)
      val withJarjestys = jarjestys.map(j => withMyonnetty ~ ("jarjestys" -> j)).getOrElse(withMyonnetty)

      withJarjestys
  }
  )
)

