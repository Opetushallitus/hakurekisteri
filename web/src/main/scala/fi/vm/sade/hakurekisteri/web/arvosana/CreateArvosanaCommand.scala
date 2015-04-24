package fi.vm.sade.hakurekisteri.web.arvosana

import org.scalatra.commands._
import java.util.UUID
import org.joda.time.LocalDate
import fi.vm.sade.hakurekisteri.web.rest.support.{LocalDateSupport, HakurekisteriCommand}
import fi.vm.sade.hakurekisteri.arvosana.{Arvio, Arvosana}


class CreateArvosanaCommand extends HakurekisteriCommand[Arvosana] with LocalDateSupport {
  val arvio: Field[String] = asType[String]("arvio.arvosana").notBlank
  val asteikko: Field[String] = asType[String]("arvio.asteikko").required.allowableValues(Arvio.asteikot:_*)
  val pisteet: Field[Option[Int]] = asType[Option[Int]]("arvio.pisteet").optional
  val suoritus: Field[String] = asType[String]("suoritus").required.validForFormat("([a-f\\d]{8}(-[a-f\\d]{4}){3}-[a-f\\d]{12}?)".r,"%s is not a valid UUID")
  val aine: Field[String] = asType[String]("aine").notBlank
  val myonnetty: Field[Option[LocalDate]] = asType[Option[LocalDate]]("myonnetty").optional
  val lisatieto: Field[Option[String]] = asType[Option[String]]("lisatieto").optional
  val valinnainen: Field[Boolean] = asType[Boolean]("valinnainen").optional
  var jarjestys: Field[Option[Int]] = binding2field(asType[Option[Int]]("jarjestys").optional)

  override def toResource(user: String): Arvosana =
    Arvosana(
      suoritus = suoritus.value.map(UUID.fromString).get,
      koetunnus = None,
      arvio = Arvio(arvio.value.get, asteikko.value.get, pisteet.value.get),
      aine = aine.value.get,
      aineyhdistelmarooli = None,
      lisatieto = lisatieto.value.get,
      valinnainen = valinnainen.value.getOrElse(false),
      myonnetty = myonnetty.value.get,
      source = user,
      jarjestys = jarjestys.value.get
    )
}

