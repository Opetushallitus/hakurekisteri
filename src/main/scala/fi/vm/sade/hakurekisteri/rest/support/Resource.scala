package fi.vm.sade.hakurekisteri.rest.support

import fi.vm.sade.hakurekisteri.storage.Identified
import java.util.UUID


trait Resource {

  def identify[R <: this.type ](id:UUID): R with Identified

}
