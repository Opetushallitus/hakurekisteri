package fi.vm.sade.hakurekisteri.rest.support

import org.json4s.CustomSerializer
import fi.vm.sade.hakurekisteri.arvosana.{Arvio410, Arvio}
import org.json4s.JsonAST.{ JString, JField, JObject}


class ArvioSerializer extends CustomSerializer[Arvio](format => (
  {
    case JObject(JField("arvosana", JString(arvosana)) :: JField("asteikko", JString("4-10")) :: Nil)  =>
      Arvio410(arvosana)
    case JObject(JField("asteikko", JString("4-10")) :: JField("arvosana", JString(arvosana)) :: Nil) => Arvio410(arvosana)



  },
  {
    case x: Arvio410 =>
      JObject(JField("arvosana", JString(x.arvosana)) :: JField("asteikko", JString("4-10")) :: Nil)
  }
  )

)
