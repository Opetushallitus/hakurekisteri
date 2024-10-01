package fi.vm.sade.hakurekisteri.rest.support

import java.util.{Date, TimeZone, UUID}

import fi.vm.sade.hakurekisteri.batchimport.{BatchState, ImportBatchSerializer}
import fi.vm.sade.hakurekisteri.ensikertalainen._
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.Maksuntila
import fi.vm.sade.hakurekisteri.integration.valintatulos.{
  Ilmoittautumistila,
  Valintatila,
  Vastaanottotila
}
import fi.vm.sade.hakurekisteri.rest.support.MenettamisenPerusteSerializer.paivamaara
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen
import org.joda.time.format.{DateTimeFormat, ISODateTimeFormat}
import org.joda.time.{DateTime, DateTimeZone}
import org.json4s.JsonAST.{JField, JObject, JString}
import org.json4s._
import org.json4s.ext.DateTimeSerializer

import scala.util.Try

trait HakurekisteriJsonSupport {

  protected implicit def jsonFormats = HakurekisteriJsonSupport.format

}

object HakurekisteriJsonSupport extends HakurekisteriJsonSupport {

  val format: Formats = HakurekisteriDefaultFormats.lossless.withBigDecimal +
    new org.json4s.ext.EnumNameSerializer(yksilollistaminen) +
    new org.json4s.ext.EnumNameSerializer(Ilmoittautumistila) +
    new org.json4s.ext.EnumNameSerializer(Valintatila) +
    new org.json4s.ext.EnumNameSerializer(Vastaanottotila) +
    new org.json4s.ext.EnumNameSerializer(Maksuntila) +
    new org.json4s.ext.EnumNameSerializer(BatchState) +
    FieldSerializer[Identified[UUID]]() +
    new UUIDSerializer +
    new IdentitySerializer +
    DateTimeSerializer +
    new LocalDateSerializer() +
    new ArvioSerializer +
    new ArvosanaSerializer +
    new AjanjaksoSerializer +
    new SuoritusSerializer +
    new LasnaoloSerializer +
    new ImportBatchSerializer +
    new MenettamisenPerusteSerializer +
    new OppijaSerializer

}

object HakurekisteriDefaultFormats extends DefaultFormats {
  lazy val Iso8601Date = ISODateTimeFormat.dateTime.withZone(DateTimeZone.UTC)
  val UTC = TimeZone.getTimeZone("UTC")

  private class ThreadLocal[A](init: => A) extends java.lang.ThreadLocal[A] with (() => A) {
    override def initialValue = init
    def apply = get
  }

  override def lossless: Formats = new DefaultFormats {
    val losslessDate = new ThreadLocal(
      new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    )

    override def dateFormatter = {
      val formatter = losslessDate()
      formatter.setTimeZone(UTC)
      formatter
    }

    override val dateFormat: DateFormat = new DateFormat {
      val short = "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}".r
      val shortTz =
        "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[Z]|[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[+-][0-9]{2}:[0-9]{2}".r
      val long = "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[.][0-9]{1,3}".r

      def format(d: Date) = new DateTime(d).toString(Iso8601Date)

      def parse(s: String) = {
        val pattern = s match {
          case short()   => "yyyy-MM-dd'T'HH:mm:ss"
          case shortTz() => "yyyy-MM-dd'T'HH:mm:ssZ"
          case long()    => "yyyy-MM-dd'T'HH:mm:ss.SSS"
          case _         => "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
        }
        Try(DateTimeFormat.forPattern(pattern).parseDateTime(s).toDate).toOption
      }

      override def timezone: TimeZone = TimeZone.getDefault
    }
  }
}

class MenettamisenPerusteSerializer
    extends CustomSerializer[MenettamisenPeruste](format =>
      (
        { case m: JObject =>
          val JString(peruste) = m \ "peruste"
          peruste match {
            case "KkVastaanotto" =>
              KkVastaanotto(paivamaara(m))
            case "SuoritettuKkTutkinto" =>
              SuoritettuKkTutkinto(paivamaara(m))
            case "OpiskeluoikeusAlkanut" =>
              OpiskeluoikeusAlkanut(paivamaara(m))
            case "SuoritettuKkTutkintoHakemukselta" =>
              val JInt(vuosi) = m \ "vuosi"
              SuoritettuKkTutkintoHakemukselta(vuosi.toInt)
            case s => throw new IllegalArgumentException(s"unknown MenettamisenPeruste $s")
          }
        },
        {
          case m: KkVastaanotto =>
            JObject(
              JField("peruste", JString(m.peruste)) :: JField(
                "paivamaara",
                JString(m.paivamaara.toString)
              ) :: Nil
            )
          case m: SuoritettuKkTutkinto =>
            JObject(
              JField("peruste", JString(m.peruste)) :: JField(
                "paivamaara",
                JString(m.paivamaara.toString)
              ) :: Nil
            )
          case m: OpiskeluoikeusAlkanut =>
            JObject(
              JField("peruste", JString(m.peruste)) :: JField(
                "paivamaara",
                JString(m.paivamaara.toString)
              ) :: Nil
            )
          case m: SuoritettuKkTutkintoHakemukselta =>
            JObject(JField("peruste", JString(m.peruste)) :: JField("vuosi", JInt(m.vuosi)) :: Nil)
        }
      )
    )

object MenettamisenPerusteSerializer {
  def paivamaara(m: JObject): DateTime = {
    val JString(paivamaara) = m \ "paivamaara"
    DateTime.parse(paivamaara)
  }
}
