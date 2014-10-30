package fi.vm.sade.hakurekisteri.batchimport

import org.scalatest.{Matchers, FlatSpec}
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods
import org.json4s.JsonAST.{JObject, JNothing, JValue}
import org.scalatra.json.JacksonJsonValueReaderProperty
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport

import org.scalatra.DefaultValue
import org.scalatra.util.ParamsValueReaderProperties
import org.scalatra.commands.{ModelValidation, CommandExecutors}
import scalaz.NonEmptyList
import org.scalatra.validation.{ValidationFail, FieldName, ValidationError}
import scala.language.implicitConversions

class ImportBatchCreationSpec extends FlatSpec
    with Matchers with JsonCommandTestSupport {

  behavior of "ImportBatchCommand"

  def command = ImportBatchCommand(
    externalIdField = "identifier",
    batchType = "testBatch",
    dataField= "batch"
  )

  val json:JValue =
    ("identifier" -> "testId") ~ ("batch" -> Seq("data2", "data1"))



  it should "parse import batch successfully" in {
    val validatedBatch = command.bindTo(json) >> (_.toValidatedResource("testuser"))
    validatedBatch.isSuccess should be (true)
  }

  it should "parse externalId successfully" in {
    val validatedBatch = command.bindTo(json) >> (_.toValidatedResource("testuser"))
    validatedBatch.resource.externalId should be (Some("testId"))
  }

  it should "parse None as externalId if missing" in {
    val validatedBatch = command.bindTo(json - "identifier") >> (_.toValidatedResource("testuser"))
    validatedBatch.resource.externalId should be (None)
  }

  it should "parse data successfully" in {
    val validatedBatch = command.bindTo(json) >> (_.toValidatedResource("testuser"))
    validatedBatch.resource.data should be (json \ "batch")
  }

  it should "parse data succesfully if all validation tests pass" in {

    val validatedBatch  = command.withValidation("great success" -> ((json: JValue) => true)).bindTo(json) >> (_.toValidatedResource("testuser"))
    validatedBatch.isSuccess should be (true)

  }

  it should "fail parsing  if a validation test fails" in {

    val validatedBatch  = command.withValidation("utter failure" -> ((json: JValue) => false)).bindTo(json) >> (_.toValidatedResource("testuser"))
    validatedBatch.isFailure should be (true)
  }

  it should "return given validation error if a validation test fails"  in {
    val validatedBatch  = command.withValidation("utter failure" -> ((json: JValue) => false)).bindTo(json) >> (_.toValidatedResource("testuser"))
    validatedBatch.failure.list should contain (ValidationError("utter failure", Some(FieldName("batch")), Some(ValidationFail)))
  }



}

trait JsonCommandTestSupport extends HakurekisteriJsonSupport with JsonMethods with JacksonJsonValueReaderProperty with ParamsValueReaderProperties with CommandExecutors with JsonModifiers {

  implicit def JsonDefaultValue: DefaultValue[JValue] = org.scalatra.DefaultValueMethods.default(JNothing)

  case class ValidationReader[T](result: ModelValidation[T]) {
    def resource: T = result.fold[T](
      errors => throw new RuntimeException(errors.toString()),
      (resource: T) => resource)

    def failure: NonEmptyList[ValidationError] = result.fold(
      errors => errors,
      (resource: T) => throw new RuntimeException("validation is succesfull")

    )
  }

  implicit def validationToExtractor[T](result: ModelValidation[T]):ValidationReader[T]  = ValidationReader(result)

  case class ValidationCommand(command: ImportBatchCommand) {

    def withValidation(validations: (String, JValue => Boolean)*) = ImportBatchCommand(command.externalIdField: String, command.batchType: String, command.dataField: String, validations:_*)

  }

  implicit def CommandToValidationCommand(command: ImportBatchCommand): ValidationCommand = ValidationCommand(command)
}


trait JsonModifiers {

  case class FieldRemover(json: JValue ) {

    def -(fieldName: String): JValue = JObject(json.filterField{case (name, value) => name != fieldName})

  }

  implicit def jvalueToFieldRemover(json:JValue): FieldRemover = FieldRemover(json)


}
