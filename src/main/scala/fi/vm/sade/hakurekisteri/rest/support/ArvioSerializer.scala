package fi.vm.sade.hakurekisteri.rest.support

import org.json4s.CustomSerializer
import fi.vm.sade.hakurekisteri.arvosana.{ArvioYo, Arvio410, Arvio}
import org.json4s.JsonAST.{JInt, JString, JField, JObject}


class ArvioSerializer extends CustomSerializer[Arvio](format => (
  {
    case JObject(JField("arvosana", JString(arvosana)) :: JField("asteikko", JString(asteikko)) :: Nil)  => Arvio(arvosana, asteikko, None)

    case JObject(JField("asteikko", JString(asteikko)) :: JField("arvosana", JString(arvosana)) :: Nil) => Arvio(arvosana, asteikko, None)

    case JObject(JField("arvosana", JString(arvosana)) :: JField("asteikko", JString(asteikko)) :: JField("pisteet", JInt(pisteet)) :: Nil)  => Arvio(arvosana, asteikko, Some(pisteet.toInt))

    case JObject(JField("asteikko", JString(asteikko)) :: JField("arvosana", JString(arvosana)) :: JField("pisteet", JInt(pisteet)) ::  Nil) => Arvio(arvosana, asteikko, Some(pisteet.toInt))


  },
  {
    case x: Arvio410 =>
      JObject(JField("arvosana", JString(x.arvosana)) :: JField("asteikko", JString("4-10")) :: Nil)
    case ArvioYo(arvosana, Some(pisteet)) =>
      JObject(JField("arvosana", JString(arvosana)) :: JField("asteikko", JString("YO")) :: JField("pisteet", JInt(pisteet)) :: Nil)
    case ArvioYo(arvosana, None) =>
      JObject(JField("arvosana", JString(arvosana)) :: JField("asteikko", JString("YO")) :: Nil)


  }
  )

)
