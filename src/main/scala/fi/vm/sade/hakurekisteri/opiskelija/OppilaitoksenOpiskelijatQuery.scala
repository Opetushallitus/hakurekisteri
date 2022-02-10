package fi.vm.sade.hakurekisteri.opiskelija

import org.joda.time.LocalDate
import fi.vm.sade.hakurekisteri.rest.support.Query
import fi.vm.sade.utils.slf4j.Logging

case class OppilaitoksenOpiskelijatQuery(
  oppilaitosOid: String,
  vuosi: Option[String] = None,
  luokkaTasot: Option[Seq[String]] = None
) extends Query[Opiskelija]

object OppilaitoksenOpiskelijatQuery extends Logging {
  def apply(params: Map[String, String]): OppilaitoksenOpiskelijatQuery = {
    val oppilaitosOid: Option[String] = params.get("oppilaitosOid")
    val vuosi: Option[String] =
      params.get("vuosi").orElse(Some[String](LocalDate.now.getYear.toString))
    val luokkaTasot: Option[Seq[String]] = params.get("luokkaTasot").map(lt => lt.split(",").toSeq)
    if (oppilaitosOid.isEmpty) {
      logger.error(
        s"oppilaitosOid was not given, throwing an exception to avoid a too large query. Parameters were $params"
      )
      throw new IllegalArgumentException(
        "Oppilaitoksen tunniste ('oppilaitosOid') on annettava."
      )
    }
    OppilaitoksenOpiskelijatQuery(oppilaitosOid.get, vuosi, luokkaTasot)
  }
}
