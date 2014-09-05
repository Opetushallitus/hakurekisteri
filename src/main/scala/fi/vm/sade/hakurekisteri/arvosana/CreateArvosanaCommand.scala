package fi.vm.sade.hakurekisteri.arvosana

import org.scalatra.commands._
import fi.vm.sade.hakurekisteri.rest.support.{LocalDateSupport, HakurekisteriCommand}
import java.util.UUID
import org.joda.time.LocalDate


class CreateArvosanaCommand extends HakurekisteriCommand[Arvosana] with LocalDateSupport {

  val arvio: Field[String] = asType[String]("arvio.arvosana").notBlank
  val asteikko: Field[String] = asType[String]("arvio.asteikko").required.allowableValues(Arvio.asteikot:_*)
  val pisteet: Field[Int] = asType[Int]("arvio.asteikko").optional
  val suoritus: Field[String] = asType[String]("suoritus").required.validForFormat("([a-f\\d]{8}(-[a-f\\d]{4}){3}-[a-f\\d]{12}?)".r,"%s is not a valid UUID")
  val aine: Field[String] = asType[String]("aine").notBlank
  val myonnetty: Field[LocalDate] = asType[LocalDate]("myonnetty").optional

  val lisatieto: Field[String] = asType[String]("lisatieto").optional
  val valinnainen: Field[Boolean] = asType[Boolean]("valinnainen").optional

  override def toResource: Arvosana = Arvosana(suoritus.value.map(UUID.fromString).get, Arvio(arvio.value.get, asteikko.value.get, pisteet.value), aine.value.get, lisatieto.value, valinnainen.value.getOrElse(false), myonnetty.value)
}


