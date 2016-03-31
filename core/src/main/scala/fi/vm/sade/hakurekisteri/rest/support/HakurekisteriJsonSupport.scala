package fi.vm.sade.hakurekisteri.rest.support

import fi.vm.sade.hakurekisteri.batchimport.ImportBatchSerializer
import fi.vm.sade.hakurekisteri.ensikertalainen.{SuoritettuKkTutkinto, KkVastaanotto, MenettamisenPeruste}
import org.joda.time.{DateTimeZone, DateTime}
import org.joda.time.format.{DateTimeFormat, ISODateTimeFormat}
import org.json4s.JsonAST.{JField, JObject, JString}
import org.json4s._
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.integration.valintatulos.{Ilmoittautumistila, Valintatila, Vastaanottotila}
import fi.vm.sade.hakurekisteri.batchimport.BatchState
import org.json4s.ext.DateParser
import java.util.{Date, TimeZone, UUID}

import scala.util.Try

trait HakurekisteriJsonSupport {

  protected implicit def jsonFormats: Formats = HakurekisteriDefaultFormats.lossless.withBigDecimal +
    new org.json4s.ext.EnumNameSerializer(yksilollistaminen) +
    new org.json4s.ext.EnumNameSerializer(Ilmoittautumistila) +
    new org.json4s.ext.EnumNameSerializer(Valintatila) +
    new org.json4s.ext.EnumNameSerializer(Vastaanottotila) +
    new org.json4s.ext.EnumNameSerializer(BatchState) +
    FieldSerializer[Identified[UUID]]() +
    new UUIDSerializer +
    new IdentitySerializer +
    HakurekisteriDateTimeSerializer +
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


object HakurekisteriJsonSupport extends HakurekisteriJsonSupport  {

  val format = jsonFormats

}


object HakurekisteriDefaultFormats extends DefaultFormats {
  lazy val Iso8601Date = ISODateTimeFormat.dateTime.withZone(DateTimeZone.UTC)
  val UTC = TimeZone.getTimeZone("UTC")

  private class ThreadLocal[A](init: => A) extends java.lang.ThreadLocal[A] with (() => A) {
    override def initialValue = init
    def apply = get
  }

  override def lossless: Formats = new DefaultFormats {
    val losslessDate = new ThreadLocal(new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))

    override def dateFormatter = {
      val formatter = losslessDate()
      formatter.setTimeZone(UTC)
      formatter
    }

    override val dateFormat: DateFormat = new DateFormat {
      val short   = "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}".r
      val shortTz = "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[Z]|[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[+-][0-9]{2}:[0-9]{2}".r
      val long    = "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[.][0-9]{1,3}".r

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
    }
  }
}

case object HakurekisteriDateTimeSerializer extends CustomSerializer[DateTime](format => (
  {
    case JString(s) => new DateTime(DateParser.parse(s, format))
    case JNull => null
  },
  {
    case d: DateTime =>
      JString(format.dateFormat.format(d.toDate))
  }
  )
)

class MenettamisenPerusteSerializer extends CustomSerializer[MenettamisenPeruste](format => (
  {
    case m: JObject  =>
      val JString(peruste) = m \ "peruste"
      val JString(paivamaara) = m \ "paivamaara"
      peruste match {
        case "KkVastaanotto" => KkVastaanotto(DateTime.parse(paivamaara))
        case "SuoritettuKkTutkinto" => SuoritettuKkTutkinto(DateTime.parse(paivamaara))
        case s => throw new IllegalArgumentException(s"unknown MenettamisenPeruste $s")
      }
  },
  {
    case m: MenettamisenPeruste =>
      JObject(JField("peruste", JString(m.peruste)) :: JField("paivamaara", JString(m.paivamaara.toString)) :: Nil)
  }
  )
)