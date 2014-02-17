package fi.vm.sade.hakurekisteri.opiskelija
import org.scalatra.commands._
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriCommand, HakurekisteriJsonSupport}
import org.joda.time.{LocalDate, DateTime}
import org.scalatra.{util, DefaultValue}
import org.scalatra.util.conversion.TypeConverter
import org.joda.time.format.{ISODateTimeFormat, DateTimeFormat}
import org.json4s._


class CreateOpiskelijaCommand extends HakurekisteriCommand[Opiskelija] {



  val oppilaitosOid: Field[String] = bind[String]("oppilaitosOid").notBlank
  val luokkataso: Field[String]  = asString("luokkataso").notBlank()
  val luokka: Field[String]  = asType[String]("luokka").notBlank()
  val henkiloOid: Field[String]  = asType[String]("henkiloOid").notBlank()
  val alkuPaiva: Field[DateTime] = asType[DateTime]("alkuPaiva").required
  val loppuPaiva: Field[DateTime] = asType[DateTime]("loppuPaiva").optional

  override def toResource: Opiskelija = Opiskelija(oppilaitosOid.value.get,luokkataso.value.get,luokka.value.get,
    henkiloOid.value.get, alkuPaiva.value.get, loppuPaiva.original.asInstanceOf[Option[DateTime]])
}