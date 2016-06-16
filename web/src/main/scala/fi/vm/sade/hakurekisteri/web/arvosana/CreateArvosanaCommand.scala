package fi.vm.sade.hakurekisteri.web.arvosana

import java.util.UUID

import fi.vm.sade.hakurekisteri.arvosana.{Arvio, Arvosana}
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriCommand, LocalDateSupport}
import org.joda.time.LocalDate
import org.scalatra.commands._


class CreateArvosanaCommand extends HakurekisteriCommand[Arvosana] with LocalDateSupport {
  val arvio: Field[String] = asType[String]("arvio.arvosana").notBlank
  val asteikko: Field[String] = asType[String]("arvio.asteikko").required.allowableValues(Arvio.asteikot:_*)
  val pisteet: Field[Option[Int]] = asType[Option[Int]]("arvio.pisteet").optional(None)
  val suoritus: Field[String] = asType[String]("suoritus").required.validForFormat("([a-f\\d]{8}(-[a-f\\d]{4}){3}-[a-f\\d]{12}?)".r,"%s is not a valid UUID")
  val aine: Field[String] = asType[String]("aine").notBlank
  val myonnetty: Field[Option[LocalDate]] = asType[Option[LocalDate]]("myonnetty").optional(None)
  val lisatieto: Field[Option[String]] = asType[Option[String]]("lisatieto").optional(None)
  val valinnainen: Field[Boolean] = asType[Boolean]("valinnainen").optional(false)
  var jarjestys: Field[Option[Int]] = binding2field(asType[Option[Int]]("jarjestys").optional(None))

  override def toResource(user: String): Arvosana =
    Arvosana(
      suoritus = suoritus.value.map(UUID.fromString).get,
      arvio = Arvio(arvio.value.get, asteikko.value.get, pisteet.value.get),
      aine = aine.value.get,
      lisatieto = lisatieto.value.get,
      valinnainen = valinnainen.value.getOrElse(false),
      myonnetty = myonnetty.value.get,
      source = user,
      lahdeArvot = Map(),
      jarjestys = jarjestys.value.get
    )
}

