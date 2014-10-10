package fi.vm.sade.hakurekisteri.opiskeluoikeus

import java.util.UUID

import fi.vm.sade.hakurekisteri.rest.support.Query
import fi.vm.sade.hakurekisteri.storage.{InMemQueryingResourceService, Identified, ResourceActor, ResourceService}
import fi.vm.sade.hakurekisteri.storage.repository.{InMemJournal, Journal, JournaledRepository}

trait OpiskeluoikeusRepository extends JournaledRepository[Opiskeluoikeus, UUID]

trait OpiskeluoikeusService extends InMemQueryingResourceService[Opiskeluoikeus, UUID] with OpiskeluoikeusRepository {
  def checkMyontaja(myontaja: Option[String])(o: Opiskeluoikeus): Boolean = myontaja match {
    case Some(oid) => o.myontaja.equals(oid)
    case None => true
  }

  def checkHenkilo(henkilo: Option[String])(o: Opiskeluoikeus): Boolean = henkilo match {
    case Some(oid) => o.henkiloOid.equals(oid)
    case None => true
  }

  val matcher: PartialFunction[Query[Opiskeluoikeus], (Opiskeluoikeus with Identified[UUID]) => Boolean] = {
    case OpiskeluoikeusQuery(henkilo, myontaja) => (o: Opiskeluoikeus with Identified[UUID]) =>
      checkHenkilo(henkilo)(o) && checkMyontaja(myontaja)(o)
  }
}

class OpiskeluoikeusActor(val journal:Journal[Opiskeluoikeus, UUID] = new InMemJournal[Opiskeluoikeus, UUID]) extends ResourceActor[Opiskeluoikeus, UUID] with OpiskeluoikeusRepository with OpiskeluoikeusService {

}