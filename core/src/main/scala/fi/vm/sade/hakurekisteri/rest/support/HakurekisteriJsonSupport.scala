package fi.vm.sade.hakurekisteri.rest.support

import fi.vm.sade.hakurekisteri.batchimport.ImportBatchSerializer
import org.joda.time.DateTime
import org.json4s.JsonAST.JString
import org.json4s._
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.integration.valintatulos.{Ilmoittautumistila, Valintatila, Vastaanottotila}
import fi.vm.sade.hakurekisteri.batchimport.BatchState
import org.json4s.ext.DateParser
import java.util.{TimeZone, UUID}

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
    new AjanjaksoSerializer +
    new SuoritusSerializer +
    new LasnaoloSerializer +
    new ImportBatchSerializer

}


object HakurekisteriJsonSupport extends HakurekisteriJsonSupport  {

  val format = jsonFormats

}


object HakurekisteriDefaultFormats extends DefaultFormats {
  private class ThreadLocal[A](init: => A) extends java.lang.ThreadLocal[A] with (() => A) {
    override def initialValue = init
    def apply = get
  }
  override def lossless: Formats = new DefaultFormats {
    val losslessDate = new ThreadLocal(new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
    override def dateFormatter = {
      val formatter = losslessDate()
      formatter.setTimeZone(TimeZone.getTimeZone("UTC"))
      formatter
    }
  }
  val UTC = TimeZone.getTimeZone("UTC")
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
  ))