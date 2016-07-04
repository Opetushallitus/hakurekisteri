package fi.vm.sade.hakurekisteri.web.validation

import scalaz._
import collection.JavaConversions._
import scala.util
import scala.util.Try
import scalaz.Success
import scalaz.Failure
import scalaz.Validation.FlatMap._


trait Validatable[T]  {
  def validatableResource(v:T):ValidationNel[String, AnyRef]

}

case class SimpleValidatable[O,T <: AnyRef](converter: O => T) extends Validatable[O] {

  override def validatableResource(v: O): ValidationNel[String, T] = {
    Try(converter(v)) match {
        case util.Success(x) => Validation.success(x).toValidationNel
        case util.Failure(t) => Validation.failure(t.getLocalizedMessage).toValidationNel
      }
  }
}

trait ScalaValidator { this: validator.api.Validator =>



  def validateData[V <: AnyRef :Validatable](data:V):ValidationNel[String, V] = {
    for (
      resource <- implicitly[Validatable[V]].validatableResource(data);
      res <- convert(validate(resource).toList)
    ) yield data
  }

  def convert[V <: AnyRef](failures:List[validator.api.ValidationResult]):ValidationNel[String, Unit] = failures match {
    case first :: rest => Failure(NonEmptyList(first.getFailedRule, rest.map(_.getFailedRule):_*))
    case _ => Success(())
  }


}
