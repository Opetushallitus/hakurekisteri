package fi.vm.sade.hakurekisteri.storage

import fi.vm.sade.hakurekisteri.storage.Identified

trait Repository[T] {

  def save(t:T):T with Identified

  def listAll():Seq[T with Identified]

}
