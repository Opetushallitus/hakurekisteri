package fi.vm.sade.hakurekisteri.opiskelija

import fi.vm.sade.hakurekisteri.rest.support.{Kausi, Query}
import fi.vm.sade.hakurekisteri.rest.support.Kausi.Kausi
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat

case class OpiskelijaQuery(henkilo: Option[String],
                           kausi: Option[Kausi],
                           vuosi: Option[String],
                           paiva: Option[DateTime],
                           oppilaitosOid: Option[String],
                           luokka: Option[String]) extends Query[Opiskelija]

object OpiskelijaQuery{



  def apply(params: Map[String,String]): OpiskelijaQuery = {
    OpiskelijaQuery(params.get("henkilo"), params.get("kausi").map(Kausi.withName), params.get("vuosi"), params.get("paiva").map(DateTime.parse(_,ISODateTimeFormat.basicDateTimeNoMillis())), params.get("oppilaitosOid"), params.get("luokka"))
  }
}
