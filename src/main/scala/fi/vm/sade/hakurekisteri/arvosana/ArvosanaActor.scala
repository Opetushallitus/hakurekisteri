package fi.vm.sade.hakurekisteri.arvosana

import fi.vm.sade.hakurekisteri.rest.support.Query
import fi.vm.sade.hakurekisteri.storage._
import fi.vm.sade.hakurekisteri.storage.repository._
import scala.Some
import java.util.UUID
import scala.concurrent.Future


trait ArvosanaRepository extends JournaledRepository[Arvosana, UUID] {

  var suoritusIndex: Map[UUID, Seq[Arvosana with Identified[UUID]]] = Option(suoritusIndex).getOrElse(Map())

  def addNew(arvosana: Arvosana with Identified[UUID]) = {
    suoritusIndex = Option(suoritusIndex).getOrElse(Map())
    suoritusIndex = suoritusIndex  + (arvosana.suoritus -> (arvosana +: suoritusIndex.get(arvosana.suoritus).getOrElse(Seq())))


  }


  override def index(old: Option[Arvosana with Identified[UUID]], current: Option[Arvosana with Identified[UUID]]) {

    def removeOld(arvosana: Arvosana with Identified[UUID]) = {
      suoritusIndex = Option(suoritusIndex).getOrElse(Map())
      suoritusIndex = suoritusIndex.get(arvosana.suoritus).
        map(_.filter((a) => a != arvosana || a.id != arvosana.id)).
        map((ns) => suoritusIndex + (arvosana.suoritus -> ns)).getOrElse(suoritusIndex)

    }



    old.foreach(removeOld)
    current.foreach(addNew)

  }

}

trait ArvosanaService extends InMemQueryingResourceService[Arvosana, UUID]  with ArvosanaRepository {


  override val optimize:PartialFunction[Query[Arvosana], Future[Seq[Arvosana with Identified[UUID]]]] = {
    case ArvosanaQuery(Some(suoritus)) =>
      Future.successful(suoritusIndex.get(suoritus).getOrElse(Seq()))
    case ArvosanaQuery(None) => Future.successful(listAll())

  }


  override val matcher: PartialFunction[Query[Arvosana], (Arvosana with Identified[UUID]) => Boolean] = {
    case ArvosanaQuery(None) => (a) => true
    case ArvosanaQuery(Some(suoritus)) => (a) => a.suoritus == suoritus
  }
}

class ArvosanaActor(val journal:Journal[Arvosana, UUID] = new InMemJournal[Arvosana, UUID]) extends ResourceActor[Arvosana, UUID] with ArvosanaRepository with ArvosanaService {
}





