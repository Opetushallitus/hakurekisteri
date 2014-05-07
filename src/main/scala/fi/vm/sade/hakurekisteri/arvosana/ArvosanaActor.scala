package fi.vm.sade.hakurekisteri.arvosana

import fi.vm.sade.hakurekisteri.rest.support.{Query, Kausi}
import Kausi._
import fi.vm.sade.hakurekisteri.storage._
import scala.Some
import com.github.nscala_time.time.Imports._
import fi.vm.sade.hakurekisteri.storage.repository._
import scala.Some


trait ArvosanaRepository extends JournaledRepository[Arvosana] {
  def identify(o:Arvosana): Arvosana with Identified = Arvosana.identify(o)
}

trait ArvosanaService extends ResourceService[Arvosana] { this: Repository[Arvosana] =>
  override val matcher: PartialFunction[Query[Arvosana], (Arvosana with Identified) => Boolean] = {
    case ArvosanaQuery(suoritus) => (a) => a.suoritus == suoritus
  }
}

class ArvosanaActor(val journal:Journal[Arvosana] = new InMemJournal[Arvosana]) extends ResourceActor[Arvosana] with ArvosanaRepository with ArvosanaService {
}





