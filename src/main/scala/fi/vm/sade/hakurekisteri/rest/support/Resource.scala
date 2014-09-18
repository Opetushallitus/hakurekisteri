package fi.vm.sade.hakurekisteri.rest.support

import fi.vm.sade.hakurekisteri.storage.Identified
import java.util.UUID


trait Resource[T] {

  val source: String

  def identify(id:T): this.type with Identified[T]

}
