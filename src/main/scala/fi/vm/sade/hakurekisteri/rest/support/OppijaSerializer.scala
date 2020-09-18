package fi.vm.sade.hakurekisteri.rest.support

import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus
import fi.vm.sade.hakurekisteri.oppija.{Oppija, Todistus}
import org.json4s.CustomSerializer
import org.json4s.Extraction._
import org.json4s.JsonAST.{JBool, JString, JValue}
import org.json4s.JsonDSL._

class OppijaSerializer
    extends CustomSerializer[Oppija](
      ser = (formats) =>
        (
          { case oppija: JValue =>
            implicit val f = formats
            val JString(oppijanumero) = oppija \ "oppijanumero"
            val opiskelu = extract[Seq[Opiskelija]](oppija \ "opiskelu")
            val suoritukset = extract[Seq[Todistus]](oppija \ "suoritukset")
            val opiskeluoikeudet = extract[Seq[Opiskeluoikeus]](oppija \ "opiskeluoikeudet")
            val ensikertalainen = oppija.findField(f => f._1 == "ensikertalainen").collect {
              case (_, JBool(b)) => b
            }
            Oppija(
              oppijanumero,
              opiskelu,
              suoritukset,
              opiskeluoikeudet,
              ensikertalainen
            )
          },
          { case oppija: Oppija =>
            ("id" -> oppija.oppijanumero) ~
              ("oppijanumero" -> oppija.oppijanumero) ~
              ("opiskelu" -> decompose(oppija.opiskelu)(formats)) ~
              ("suoritukset" -> decompose(oppija.suoritukset)(formats)) ~
              ("opiskeluoikeudet" -> decompose(oppija.opiskeluoikeudet)(formats)) ~
              ("ensikertalainen" -> oppija.ensikertalainen.map(JBool(_)))
          }
        )
    )
