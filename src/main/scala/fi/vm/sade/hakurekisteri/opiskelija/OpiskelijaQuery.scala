package fi.vm.sade.hakurekisteri.opiskelija

import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.rest.support.{Kausi, Query}
import fi.vm.sade.hakurekisteri.rest.support.Kausi.Kausi
import fi.vm.sade.utils.slf4j.Logging
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat

import scala.util.Try

case class OpiskelijaQuery(
  henkilo: Option[String] = None,
  kausi: Option[Kausi] = None,
  vuosi: Option[String] = None,
  paiva: Option[DateTime] = None,
  oppilaitosOid: Option[String] = None,
  luokka: Option[String] = None,
  source: Option[String] = None
) extends Query[Opiskelija]

object OpiskelijaQuery extends Logging {
  def apply(params: Map[String, String]): OpiskelijaQuery = {
    def extractDate(s: String) = Try(DateTime.parse(s, ISODateTimeFormat.dateTimeNoMillis()))
      .recoverWith { case _: Exception =>
        Try(DateTime.parse(s, ISODateTimeFormat.basicDateTimeNoMillis()))
      }
      .recoverWith { case _: Exception =>
        Try(DateTime.parse(s, ISODateTimeFormat.basicDateTime()))
      }
      .recoverWith { case _: Exception => Try(DateTime.parse(s, ISODateTimeFormat.dateTime())) }
      .get

    val personOid: Option[String] = params.get("henkilo")
    val kausi: Option[Kausi] = params.get("kausi").map(Kausi.withName)
    val vuosi: Option[String] = params.get("vuosi")
    val paiva: Option[DateTime] = params.get("paiva").map(extractDate)
    val oppilaitosOid: Option[String] = params.get("oppilaitosOid")
    val luokka: Option[String] = params.get("luokka")
    val source: Option[String] = params.get("source")
    if (personOid.isEmpty && oppilaitosOid.isEmpty) {
      logger.error(
        s"Both personOid and oppilaitosOid were empty, throwing an exception to avoid a too large query. Parameters were $params"
      )
      throw new IllegalArgumentException(
        "Joko oppijanumero ('henkilo') tai oppilaitoksen tunniste ('oppilaitosOid') on annettava. Vaikuttaa bugilta."
      )
    }
    OpiskelijaQuery(personOid, kausi, vuosi, paiva, oppilaitosOid, luokka, source)
  }
}

case class OpiskelijaHenkilotQuery(henkilot: PersonOidsWithAliases) extends Query[Opiskelija]
