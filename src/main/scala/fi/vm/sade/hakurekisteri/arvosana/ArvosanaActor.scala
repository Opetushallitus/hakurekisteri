package fi.vm.sade.hakurekisteri.arvosana

import fi.vm.sade.hakurekisteri.rest.support.Query
import fi.vm.sade.hakurekisteri.storage._
import fi.vm.sade.hakurekisteri.storage.repository._
import scala.Some
import java.util.UUID
import scala.concurrent.Future


trait ArvosanaRepository extends JournaledRepository[Arvosana] {

  var suoritusIndex: Map[UUID, Seq[Arvosana with Identified]] = Option(suoritusIndex).getOrElse(Map())
  var suoritusIndexSnapShot: Map[UUID, Seq[Arvosana with Identified]] = Option(suoritusIndexSnapShot).getOrElse(Map())

  def addNew(arvosana: Arvosana with Identified) = {
    suoritusIndexSnapShot = Option(suoritusIndexSnapShot).getOrElse(Map())
    suoritusIndexSnapShot = suoritusIndexSnapShot  + (arvosana.suoritus -> (arvosana +: suoritusIndexSnapShot.get(arvosana.suoritus).getOrElse(Seq())))


  }

  override def indexSwapSnapshot() {
    suoritusIndex = suoritusIndexSnapShot

  }

  override def indexRunning(old: Option[Arvosana with Identified], current: Option[Arvosana with Identified]) {suoritusIndex = indexcurrent(old, current, suoritusIndex)}


  override def index(old: Option[Arvosana with Identified], current: Option[Arvosana with Identified]) {suoritusIndexSnapShot = indexcurrent(old, current, suoritusIndexSnapShot)}

  def indexcurrent(old: Option[Arvosana with Identified], current: Option[Arvosana with Identified], index: Map[UUID, Seq[Arvosana with Identified]]) =  {

    var updatedIndex = index

    def removeOld(arvosana: Arvosana with Identified) = {
      updatedIndex = Option(updatedIndex).getOrElse(Map())
      updatedIndex = updatedIndex.get(arvosana.suoritus).
        map(_.filter((a) => a != arvosana || a.id != arvosana.id)).
        map((ns) => updatedIndex + (arvosana.suoritus -> ns)).getOrElse(suoritusIndexSnapShot)

    }



    old.foreach(removeOld)
    current.foreach(addNew)
    updatedIndex
  }

    def identify(o:Arvosana): Arvosana with Identified = Arvosana.identify(o)
}

trait ArvosanaService extends ResourceService[Arvosana]  with ArvosanaRepository {


  override val optimize:PartialFunction[Query[Arvosana], Future[Seq[Arvosana with Identified]]] = {
    case ArvosanaQuery(Some(suoritus)) =>
      Future.successful(suoritusIndex.get(suoritus).getOrElse(Seq()))
    case ArvosanaQuery(None) => Future.successful(listAll())

  }


  override val matcher: PartialFunction[Query[Arvosana], (Arvosana with Identified) => Boolean] = {
    case ArvosanaQuery(None) => (a) => true
    case ArvosanaQuery(Some(suoritus)) => (a) => a.suoritus == suoritus
  }
}

class ArvosanaActor(val journal:Journal[Arvosana] = new InMemJournal[Arvosana]) extends ResourceActor[Arvosana] with ArvosanaRepository with ArvosanaService {
}





