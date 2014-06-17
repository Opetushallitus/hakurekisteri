package fi.vm.sade.hakurekisteri.rest.support

import fi.vm.sade.hakurekisteri.storage.Identified
import java.util.UUID


trait Resource[T] {

  def identify(id:T): this.type with Identified[T]

}
