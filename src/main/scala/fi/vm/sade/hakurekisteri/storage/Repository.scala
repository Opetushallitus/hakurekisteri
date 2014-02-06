package fi.vm.sade.hakurekisteri.storage

import fi.vm.sade.hakurekisteri.storage.Identified
import java.util.UUID
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija

trait Repository[T] {

  def save(t:T):T with Identified

  def listAll():Seq[T with Identified]

}

trait InMemRepository[T] extends Repository[T] {
  var store:Map[UUID,T with Identified] = Map()

  def save(o: T ): T with Identified = {
    val oid = identify(o)
    store = store + (oid.id -> oid)
    oid
  }


  def identify(o: T): T with Identified

  def listAll(): Seq[T with Identified] = {
    store.values.toSeq
  }


}
