package fi.vm.sade.hakurekisteri.rest.support

import org.json4s.JValue
import org.json4s.JsonAST.{JNull, JString}

trait ValidationSupport {
  def checkMandatory(required: Seq[String], input: Map[String, JValue]): Seq[String] = {
    var errors: Seq[String] = Seq()
    required.foreach(key => {
      try {
        val value: Option[JValue] = input.get(key)
        if (value.isEmpty) {
          val msg = String.format("Pakollista kenttää %s ei löydy", key)
          errors = errors :+ msg
        } else if (JNull.equals(value.get) || JString("").equals(value.get)) {
          val msg = String.format("Pakollinen kenttä %s on null tai tyhjä ", key)
          errors = errors :+ msg
        }
      } catch {
        case e: Exception =>
          errors = errors :+ String.format("Virhe kentässä %s: %s", key, e.getMessage)
      }
    })
    errors
  }
}
