package fi.vm.sade.hakurekisteri.web.validation

import scalaz.{Failure, Success, NonEmptyList, Validation}
import collection.JavaConversions._


trait Validatable[T] {
  def validatableResource(v:T):AnyRef

}

trait ScalaValidator { this: validator.api.Validator =>

  def convert(resource: AnyRef, failures:List[validator.api.ValidationResult]):Validation[NonEmptyList[String], AnyRef] = failures match {
    case failures if failures.isEmpty => Success(resource)
    case first :: rest => Failure(NonEmptyList(first.getFailedRule, rest.map(_.getFailedRule):_*))
  }

  def validateData[V <: AnyRef :Validatable](data:V):Validation[NonEmptyList[String], AnyRef] = {

    val resource = implicitly[Validatable[V]].validatableResource(data)
    println(resource)
    convert(data, validate(resource).toList)
  }


}
