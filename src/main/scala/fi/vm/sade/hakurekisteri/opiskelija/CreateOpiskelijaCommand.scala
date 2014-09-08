package fi.vm.sade.hakurekisteri.opiskelija
import org.scalatra.commands._
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriCommand
import org.joda.time.DateTime
import org.json4s._
import scala.util.Try

class CreateOpiskelijaCommand extends HakurekisteriCommand[Opiskelija] {
  val oppilaitosOid: Field[String] = bind[String]("oppilaitosOid").notBlank
  val luokkataso: Field[String]  = asString("luokkataso").notBlank()
  val luokka: Field[String]  = asType[String]("luokka").notBlank()
  val henkiloOid: Field[String]  = asType[String]("henkiloOid").notBlank()
  val alkuPaiva: Field[String] = asType[String]("alkuPaiva").notBlank().validate(jsonFormats.dateFormat.parse(_).isDefined)
  val loppuPaiva: Field[String] = asType[String]("loppuPaiva").optional.validate(jsonFormats.dateFormat.parse(_).isDefined)

  override def toResource(user: String): Opiskelija = {
    Opiskelija(oppilaitosOid.value.get,
              luokkataso.value.get,
              luokka.value.get,
              henkiloOid.value.get,
              JString(alkuPaiva.value.get).extract[DateTime],
              loppuPaiva.value.flatMap((s) => Try(JString(s).extract[DateTime]).toOption), source = user)}
}