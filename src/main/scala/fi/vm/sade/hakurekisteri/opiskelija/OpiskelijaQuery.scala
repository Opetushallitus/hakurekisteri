package fi.vm.sade.hakurekisteri.opiskelija

import fi.vm.sade.hakurekisteri.rest.support.{Kausi, Query}
import fi.vm.sade.hakurekisteri.rest.support.Kausi.Kausi
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import scala.util.Try

case class OpiskelijaQuery(henkilo: Option[String],
                           kausi: Option[Kausi],
                           vuosi: Option[String],
                           paiva: Option[DateTime],
                           oppilaitosOid: Option[String],
                           luokka: Option[String]) extends Query[Opiskelija]

object OpiskelijaQuery{



  def apply(params: Map[String,String]): OpiskelijaQuery = {

    def extractDate(s:String) = Try(DateTime.parse(s,ISODateTimeFormat.dateTimeNoMillis())).
      recoverWith{case _ : Exception => Try(DateTime.parse(s, ISODateTimeFormat.basicDateTimeNoMillis()))}.get
    OpiskelijaQuery(params.get("henkilo"), params.get("kausi").map(Kausi.withName), params.get("vuosi"), params.get("paiva").map(extractDate), params.get("oppilaitosOid"), params.get("luokka"))
  }
}
