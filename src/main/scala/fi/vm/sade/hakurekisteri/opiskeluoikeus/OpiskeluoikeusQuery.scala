package fi.vm.sade.hakurekisteri.opiskeluoikeus

import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.rest.support.Query
import fi.vm.sade.utils.slf4j.Logging

case class OpiskeluoikeusQuery(henkilo: Option[String] = None, myontaja: Option[String] = None)
    extends Query[Opiskeluoikeus]

object OpiskeluoikeusQuery extends Logging {
  def apply(params: Map[String, String]): OpiskeluoikeusQuery = {
    val personOid: Option[String] = params.get("henkilo")
    val oppilaitosOid: Option[String] = params.get("myontaja")
    if (personOid.isEmpty && oppilaitosOid.isEmpty) {
      logger.error(
        s"Both henkilo and myontaja parameters were empty, throwing an exception to avoid a too large query."
      )
      throw new IllegalArgumentException("Vähintään yksi hakuehto on pakollinen")
    }
    OpiskeluoikeusQuery(personOid, oppilaitosOid)
  }
}

case class OpiskeluoikeusHenkilotQuery(
  henkilot: PersonOidsWithAliases,
  myontaja: Option[String] = None
) extends Query[Opiskeluoikeus]
