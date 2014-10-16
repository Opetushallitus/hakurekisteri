package fi.vm.sade.hakurekisteri.opiskeluoikeus

import java.util.UUID

import akka.event.Logging
import fi.vm.sade.hakurekisteri.opiskeluoikeus.OpiskeluoikeusQuery
import fi.vm.sade.hakurekisteri.rest.support.Query
import fi.vm.sade.hakurekisteri.storage.{InMemQueryingResourceService, Identified, ResourceActor}
import fi.vm.sade.hakurekisteri.storage.repository.{InMemJournal, Journal, JournaledRepository}

import scala.concurrent.Future

trait OpiskeluoikeusRepository extends JournaledRepository[Opiskeluoikeus, UUID] {

  var henkiloIndex: Map[String, Seq[Opiskeluoikeus with Identified[UUID]]] = Option(henkiloIndex).getOrElse(Map())

  def addNew(opiskeluoikeus: Opiskeluoikeus with Identified[UUID]) = {
    henkiloIndex = Option(henkiloIndex).getOrElse(Map())
    henkiloIndex = henkiloIndex  + (opiskeluoikeus.henkiloOid -> (opiskeluoikeus +: henkiloIndex.getOrElse(opiskeluoikeus.henkiloOid, Seq())))
  }

  override def index(old: Option[Opiskeluoikeus with Identified[UUID]], current: Option[Opiskeluoikeus with Identified[UUID]]) {
    def removeOld(opiskeluoikeus: Opiskeluoikeus with Identified[UUID]) = {
      henkiloIndex = Option(henkiloIndex).getOrElse(Map())
      henkiloIndex = henkiloIndex.get(opiskeluoikeus.henkiloOid).
        map(_.filter((a) => a != opiskeluoikeus || a.id != opiskeluoikeus.id)).
        map((ns) => henkiloIndex + (opiskeluoikeus.henkiloOid -> ns)).getOrElse(henkiloIndex)
    }

    old.foreach(removeOld)
    current.foreach(addNew)
  }

}

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

  override val optimize: PartialFunction[Query[Opiskeluoikeus], Future[Seq[Opiskeluoikeus with Identified[UUID]]]] = {
    case OpiskeluoikeusQuery(Some(henkilo), None) =>
      Future.successful(henkiloIndex.getOrElse(henkilo, Seq()))

    case OpiskeluoikeusQuery(Some(henkilo), myontaja) =>
      val filtered = henkiloIndex.getOrElse(henkilo, Seq())
      executeQuery(filtered)(OpiskeluoikeusQuery(Some(henkilo), myontaja))

  }

}

class OpiskeluoikeusActor(val journal:Journal[Opiskeluoikeus, UUID] = new InMemJournal[Opiskeluoikeus, UUID]) extends ResourceActor[Opiskeluoikeus, UUID] with OpiskeluoikeusRepository with OpiskeluoikeusService {
  override val logger = Logging(context.system, this)
}