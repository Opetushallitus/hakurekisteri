package fi.vm.sade.hakurekisteri.opiskeluoikeus

import fi.vm.sade.hakurekisteri.rest.support.Query

case class OpiskeluoikeusQuery(henkilo: Option[String] = None, myontaja: Option[String] = None) extends Query[Opiskeluoikeus]

object OpiskeluoikeusQuery {
  def apply(params: Map[String,String]): OpiskeluoikeusQuery = {
    OpiskeluoikeusQuery(params.get("henkilo"), params.get("myontaja"))
  }
}
