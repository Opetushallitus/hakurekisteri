package fi.vm.sade.hakurekisteri.batchimport

import org.scalatest.{Matchers, FlatSpec}
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods
import org.json4s.JsonAST.{JObject, JValue}
import org.scalatra.json.JacksonJsonValueReaderProperty
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport

import org.scalatra.util.ParamsValueReaderProperties
import org.scalatra.commands._
import scala.xml.Elem
import scalaz.NonEmptyList
import org.scalatra.validation.{ValidationFail, ValidationError}
import scala.language.implicitConversions
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import org.scalatra.validation.FieldName
import scala.Some


class ImportBatchCreationSpec extends FlatSpec
    with Matchers with JsonCommandTestSupport {

  behavior of "ImportBatchCommand"

  def command = ImportBatchCommand(
    externalIdField = "identifier",
    batchType = "testBatch",
    dataField= "batch"
  )

  val xml = <batchdata>
    <identifier>testId</identifier>
    <batch>
      <data/>
      <data/>
    </batch>
  </batchdata>

  val json: JValue =
    ("identifier" -> "testId") ~ ("batch" -> xml.toString)

  it should "parse import batch successfully" in {
    val validatedBatch = Await.result(command.bindTo(json) >> (_.toValidatedResource("testuser")), 10.seconds)
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
    validatedBatch.resource.data should be (xml)
  }

  it should "parse data succesfully if all validation tests pass" in {
    val validatedBatch = Await.result(command.withValidation("great success" -> ((j: Elem) => true)).bindTo(json) >> (_.toValidatedResource("testuser")), 10.seconds)
    validatedBatch.isSuccess should be (true)
  }

  it should "fail parsing  if a validation test fails" in {
    val validatedBatch = Await.result(command.withValidation("utter failure" -> ((j: Elem) => false)).bindTo(json) >> (_.toValidatedResource("testuser")), 10.seconds)
    validatedBatch.isFailure should be (true)
  }

  it should "return given validation error if a validation test fails" in {
    val validatedBatch = command.withValidation("utter failure" -> ((json: Elem) => false)).bindTo(json) >> (_.toValidatedResource("testuser"))
    validatedBatch.failure.list should contain(ValidationError("utter failure", Some(FieldName("batch")), Some(ValidationFail)))
  }
}

trait JsonCommandTestSupport extends HakurekisteriJsonSupport with JsonMethods with JacksonJsonValueReaderProperty with ParamsValueReaderProperties  with JsonModifiers {
  implicit def asyncSyncExecutor[T <: Command, S](handler: T => Future[ModelValidation[S]]): CommandExecutor[T, Future[ModelValidation[S]]] =
    new CommandExecutor[T, Future[ModelValidation[S]]](handler) {
      def wait(handler: T => Future[ModelValidation[S]]): T => ModelValidation[S]  = (cmd) => Await.result(handler(cmd), 30.seconds)
      val inner = new BlockingCommandExecutor(wait(handler))
      override def execute(command: T): Future[ModelValidation[S]] = Future.successful(inner.execute(command))
    }

  case class ValidationReader[T](result: ModelValidation[T]) {
    def resource: T = result.fold[T](
      errors => throw new RuntimeException(errors.toString()),
      (resource: T) => resource
    )

    def failure: NonEmptyList[ValidationError] = result.fold(
      errors => errors,
      (resource: T) => throw new RuntimeException("validation is successful")
    )
  }

  implicit def validationToExtractor[T](result: Future[ModelValidation[T]]):ValidationReader[T]  = ValidationReader(Await.result(result, 10.seconds))

  case class ValidationCommand(command: ImportBatchCommand) {
    def withValidation(validations: (String, Elem => Boolean)*) = ImportBatchCommand(command.externalIdField: String, command.batchType: String, command.dataField: String, validations:_*)
  }

  implicit def CommandToValidationCommand(command: ImportBatchCommand): ValidationCommand = ValidationCommand(command)
}

trait JsonModifiers {
  case class FieldRemover(json: JValue ) {
    def -(fieldName: String): JValue = JObject(json.filterField{case (name, value) => name != fieldName})
  }

  implicit def jvalueToFieldRemover(json:JValue): FieldRemover = FieldRemover(json)
}
