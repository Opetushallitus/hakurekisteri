package fi.vm.sade.hakurekisteri.unit

import _root_.java.{util => java}

import fi.vm.sade.hakurekisteri.TestResource
import fi.vm.sade.hakurekisteri.web.validation.{ScalaValidator, SimpleValidatable, Validatable}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}
import validator.api.{ValidationResult, Validator}

import scalaz.NonEmptyList

class ScalaValidatorSpec extends FlatSpec with Matchers with MockitoSugar {

  behavior of "Scala validation"

  it should "use converter prior validation" in {
    val extractor = (tr: TestResource) => tr.name

    implicit val trv: Validatable[TestResource] = SimpleValidatable(extractor)

    val tested = TestResource("testing")
    val mockValidator = mock[Validator]

    when(mockValidator.validate(tested.name)).thenReturn(new java.ArrayList[ValidationResult]())

    val sv = TestValidator(mockValidator)

    sv.validateData(tested)

    verify(mockValidator).validate(extractor(tested))

  }

  it should "result in success for empty list form validation" in {
    val extractor = (tr: TestResource) => tr.name

    implicit val trv: Validatable[TestResource] = SimpleValidatable(extractor)

    val tested = TestResource("testing")
    val mockValidator = mock[Validator]

    when(mockValidator.validate(tested.name)).thenReturn(new java.ArrayList[ValidationResult]())

    val sv = TestValidator(mockValidator)

    sv.validateData(tested).isSuccess should be(true)

  }

  it should "result in failure for non empty list form validation" in {
    val extractor = (tr: TestResource) => tr.name

    implicit val trv: Validatable[TestResource] = SimpleValidatable(extractor)

    val tested = TestResource("testing")
    val mockValidator = mock[Validator]

    when(mockValidator.validate(tested.name))
      .thenReturn(java.Arrays.asList[ValidationResult](TestValidationResult("failure")))

    val sv = TestValidator(mockValidator)

    sv.validateData(tested).isFailure should be(true)

  }

  it should "return a non empty list containing failed rules in failure" in {
    val extractor = (tr: TestResource) => tr.name

    implicit val trv: Validatable[TestResource] = SimpleValidatable(extractor)

    val tested = TestResource("testing")
    val mockValidator = mock[Validator]

    when(mockValidator.validate(tested.name))
      .thenReturn(java.Arrays.asList[ValidationResult](TestValidationResult("failure")))

    val sv = TestValidator(mockValidator)

    sv.validateData(tested).swap.getOrElse(NonEmptyList("foo")).head should be("failure")

  }

}

case class TestValidator(validator: Validator) extends Validator with ScalaValidator {

  def validate(v: AnyRef): java.List[ValidationResult] = validator.validate(v)

}

case class TestValidationResult(rule: String, resource: AnyRef = None) extends ValidationResult {
  override def getFailedRule: String = rule

  override def getResource: AnyRef = resource
}
