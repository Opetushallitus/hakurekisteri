package fi.vm.sade.hakurekisteri.opiskelija

import fi.vm.sade.hakurekisteri.rest.support.{Kausi, Query}
import fi.vm.sade.hakurekisteri.rest.support.Kausi.Kausi
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import scala.util.Try

case class OpiskelijaQuery(henkilo: Option[String] = None,
                           kausi: Option[Kausi] = None,
                           vuosi: Option[String] = None,
                           paiva: Option[DateTime] = None,
                           oppilaitosOid: Option[String] = None,
                           luokka: Option[String] = None) extends Query[Opiskelija]

object OpiskelijaQuery{



  def apply(params: Map[String,String]): OpiskelijaQuery = {

    def extractDate(s:String) = Try(DateTime.parse(s,ISODateTimeFormat.dateTimeNoMillis())).
      recoverWith{case _ : Exception => Try(DateTime.parse(s, ISODateTimeFormat.basicDateTimeNoMillis()))}.
      recoverWith{case _ : Exception => Try(DateTime.parse(s, ISODateTimeFormat.basicDateTime()))}.
      recoverWith{case _ : Exception => Try(DateTime.parse(s, ISODateTimeFormat.dateTime()))}.get
    OpiskelijaQuery(params.get("henkilo"), params.get("kausi").map(Kausi.withName), params.get("vuosi"), params.get("paiva").map(extractDate), params.get("oppilaitosOid"), params.get("luokka"))
  }
}
