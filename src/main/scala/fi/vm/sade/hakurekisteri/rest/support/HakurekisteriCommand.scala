package fi.vm.sade.hakurekisteri.rest.support

import org.scalatra.commands.{ModelValidation, JsonCommand}
import fi.vm.sade.hakurekisteri.suoritus.{Komoto, Suoritus}
import org.scalatra.validation.{UnknownError, ValidationError}
import scala.util.control.Exception._
import org.scalatra.validation._
import scalaz._, Scalaz._

trait HakurekisteriCommand[R] extends  JsonCommand  with HakurekisteriJsonSupport{

  def toResource: R

  def errorFail(ex: Throwable) = ValidationError(ex.getMessage, UnknownError).failNel

  def toValidatedResource: ModelValidation[R] = {
    allCatch.withApply(errorFail) {
      toResource.successNel
    }
  }
}
