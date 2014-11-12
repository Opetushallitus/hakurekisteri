package fi.vm.sade.hakurekisteri.batchimport

import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.json4s.JsonAST.{JObject, JValue}
import org.json4s.jackson.JsonMethods
import org.scalatest.{FlatSpec, Matchers}
import org.scalatra.commands._
import org.scalatra.json.JacksonJsonValueReaderProperty
import org.scalatra.util.ParamsValueReaderProperties
import org.scalatra.validation.{FieldName, ValidationError}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.implicitConversions
import scala.xml.Elem
import scalaz.Scalaz._
import scalaz._



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

  val params =
    Map("batch" -> xml.toString)

  it should "parse import batch successfully" in {
    val validatedBatch = Await.result(command.bindTo(Map("identifier" -> "testId", "batch" -> xml.toString)) >> (_.toValidatedResource("testuser")), 10.seconds)
    validatedBatch.isSuccess should be (true)
  }

  it should "parse externalId successfully" in {
    val validatedBatch = command.bindTo(params) >> (_.toValidatedResource("testuser"))
    validatedBatch.resource.externalId should be (Some("testId"))
  }

  it should "parse None as externalId if missing" in {
    val validatedBatch = command.bindTo(Map("batch" -> (xml - "identifier").toString())) >> (_.toValidatedResource("testuser"))
    validatedBatch.resource.externalId should be (None)
  }

  it should "parse data successfully" in {
    val validatedBatch = command.bindTo(params) >> (_.toValidatedResource("testuser"))
    validatedBatch.resource.data should be (xml)
  }

  it should "parse data succesfully if all validation tests pass" in {
    val validatedBatch = Await.result(command.withValidation((j: Elem) => j.successNel).bindTo(params) >> (_.toValidatedResource("testuser")), 10.seconds)
    validatedBatch.isSuccess should be (true)
  }

  it should "fail parsing  if a validation test fails" in {
    val validatedBatch = Await.result(command.withValidation((j: Elem) => ValidationError("utter failure", FieldName("batch")).failNel).bindTo(params) >> (_.toValidatedResource("testuser")), 10.seconds)
    validatedBatch.isFailure should be (true)
  }

  it should "return given validation error if a validation test fails" in {
    val validatedBatch = command.withValidation((j: Elem) => ValidationError("utter failure", FieldName("batch")).failNel).bindTo(params) >> (_.toValidatedResource("testuser"))
    validatedBatch.failure.list should contain(ValidationError("utter failure", Some(FieldName("batch")), None))
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
    def withValidation(validations:  (Elem => ModelValidation[Elem])*) = ImportBatchCommand(command.externalIdField: String, command.batchType: String, command.dataField: String, validations:_*)
  }

  implicit def CommandToValidationCommand(command: ImportBatchCommand): ValidationCommand = ValidationCommand(command)
}

trait JsonModifiers {
  case class FieldRemover(json: JValue ) {
    def -(fieldName: String): JValue = JObject(json.filterField{case (name, value) => name != fieldName})
  }

  case class XmlRemover(xml:Elem) {
    def -(tagName: String): Elem = xml.copy(child = xml.child.filterNot(_.label == tagName))
  }


  implicit def elemToFieldRemover(elem:Elem): XmlRemover = XmlRemover(elem)

  implicit def jvalueToFieldRemover(json:JValue): FieldRemover = FieldRemover(json)
}
