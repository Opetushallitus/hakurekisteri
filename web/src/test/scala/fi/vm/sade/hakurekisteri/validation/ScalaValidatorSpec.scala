package fi.vm.sade.hakurekisteri.validation

import org.scalatest.{Matchers, FlatSpec}
import validator.api.{ValidationResult, Validator}
import _root_.java.{util => java}
import org.scalatest.mock.MockitoSugar
import fi.vm.sade.hakurekisteri.web.validation.{Validatable, ScalaValidator}
import fi.vm.sade.hakurekisteri.TestResource
import org.mockito.Mockito._
import org.mockito.Mockito
import scalaz.NonEmptyList

class ScalaValidatorSpec extends FlatSpec with Matchers with MockitoSugar {

  behavior of "Scala validation"

  it should "use converter prior validation" in {
    val extractor = (tr:TestResource) => tr.name

    implicit val trv:Validatable[TestResource] = new Validatable[TestResource] {
      override def validatableResource(v: TestResource): AnyRef = extractor(v)
    }

    val tested = TestResource("testing")
    val mockValidator = mock[Validator]

    when(mockValidator.validate(tested.name)).thenReturn(new java.ArrayList[ValidationResult]())

    val sv = TestValidator(mockValidator)

    sv.validateData(tested)

    verify(mockValidator).validate(extractor(tested))

  }

  it should "result in success for empty list form validation" in {
    implicit val trv:Validatable[TestResource] = new Validatable[TestResource] {
      override def validatableResource(v: TestResource): AnyRef = v.name
    }

    val tested = TestResource("testing")
    val mockValidator = mock[Validator]

    when(mockValidator.validate(tested.name)).thenReturn(new java.ArrayList[ValidationResult]())

    val sv = TestValidator(mockValidator)

    sv.validateData(tested).isSuccess should be (true)

  }

  it should "result in failure for non empty list form validation" in {
    implicit val trv:Validatable[TestResource] = new Validatable[TestResource] {
      override def validatableResource(v: TestResource): AnyRef = v.name
    }

    val tested = TestResource("testing")
    val mockValidator = mock[Validator]

    when(mockValidator.validate(tested.name)).thenReturn(java.Arrays.asList[ValidationResult](TestValidationResult("failure")))

    val sv = TestValidator(mockValidator)

    sv.validateData(tested).isFailure should be (true)

  }

  it should "return a non empty list containing failed rules in failure" in {
    implicit val trv:Validatable[TestResource] = new Validatable[TestResource] {
      override def validatableResource(v: TestResource): AnyRef = v.name
    }

    val tested = TestResource("testing")
    val mockValidator = mock[Validator]

    when(mockValidator.validate(tested.name)).thenReturn(java.Arrays.asList[ValidationResult](TestValidationResult("failure")))

    val sv = TestValidator(mockValidator)

    sv.validateData(tested).swap.getOrElse(NonEmptyList("foo")).head should be ("failure")

  }






}

case class TestValidator(validator: Validator) extends Validator with ScalaValidator {

  def validate(v:AnyRef):java.List[ValidationResult] = validator.validate(v)

}

case class TestValidationResult(rule:String, resource:AnyRef = None) extends ValidationResult {
  override def getFailedRule: String = rule

  override def getResource: AnyRef = resource
}



