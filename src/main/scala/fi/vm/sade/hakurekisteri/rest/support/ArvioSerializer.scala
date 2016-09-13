package fi.vm.sade.hakurekisteri.rest.support

import org.json4s.CustomSerializer
import fi.vm.sade.hakurekisteri.arvosana._
import org.json4s.JsonAST.{JField, JInt, JObject, JString}


class ArvioSerializer extends CustomSerializer[Arvio](format => (
  {
    case arvio:JObject  =>
      val JString(arvosana) = arvio \ "arvosana"
      val JString(asteikko) = arvio \ "asteikko"
      val pisteet = (arvio \ "pisteet").toOption.flatMap{
        case JInt(yhteispisteet) => Some(yhteispisteet.toInt)
        case _ => None
      }
      Arvio(arvosana, asteikko, pisteet)
  },
  {
    case x: Arvio410 =>
      JObject(JField("arvosana", JString(x.arvosana)) :: JField("asteikko", JString("4-10")) :: Nil)
    case ArvioYo(arvosana, Some(pisteet)) =>
      JObject(JField("arvosana", JString(arvosana)) :: JField("asteikko", JString("YO")) :: JField("pisteet", JInt(pisteet)) :: Nil)
    case ArvioYo(arvosana, None) =>
      JObject(JField("arvosana", JString(arvosana)) :: JField("asteikko", JString("YO")) :: Nil)
    case ArvioOsakoe(osakoepisteet) =>
      JObject(JField("arvosana", JString(osakoepisteet)) :: JField("asteikko", JString("OSAKOE")) :: Nil)
    case ArvioHyvaksytty(arvosana) =>
      JObject(JField("arvosana", JString(arvosana)) :: JField("asteikko", JString("HYVAKSYTTY")) :: Nil)
  }
  )
)
