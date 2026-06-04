package fi.vm.sade.hakurekisteri.integration.ytl

import java.io._
import java.nio.file.{Files, Paths}
import java.text.SimpleDateFormat
import fi.vm.sade.properties.OphProperties
import org.apache.commons.io.IOUtils
import org.slf4j.{Logger, LoggerFactory}
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  GetObjectRequest,
  ListObjectsV2Request,
  PutObjectRequest
}

import scala.collection.JavaConverters._
import scala.util.{Failure, Success}
import scala.util.Try

trait FileAccess {

  def fileName(groupUuid: String, uuid: String) =
    s"${now()}_${groupUuid}_${uuid}_student-results.zip"

  def now() = new SimpleDateFormat("dd-MM-yyyy_HH-mm").format(new java.util.Date())

  def read(uuid: String): Iterator[InputStream]
  def write(groupUuid: String, uuid: String)(input: InputStream)

  protected def closeInCaseOfFailure[T <: Closeable](ci: List[Try[T]]): List[T] = ci match {
    case i if i.find(_.isFailure).isDefined => {
      i.filter(_.isSuccess).foreach(s => IOUtils.closeQuietly(s.get))
      throw i.find(_.isFailure).get.failed.get
    }
    case i => i.map(_.get)
  }
}

object YtlFileSystem {

  def apply(config: OphProperties): YtlFileSystem =
    config.getOrElse("ytl.s3.enabled", "false") match {
      case p if "TRUE".equalsIgnoreCase(p) => new YtlS3FileSystem(config)
      case _                               => new YtlFileFileSystem(config)
    }
}

abstract class YtlFileSystem(config: OphProperties) extends FileAccess

class YtlFileFileSystem(val config: OphProperties) extends YtlFileSystem(config) {
  val logger: Logger = LoggerFactory.getLogger(getClass)

  val directoryPath: File =
    Option(config.getOrElse("ytl.http.download.directory", null))
      .map(new File(_))
      .getOrElse {
        logger.warn(
          "Using OS temporary directory for YTL files since 'ytl.http.download.directory' configuration is missing!"
        )
        Files.createTempDirectory("temp-ytl-download").toFile
      }

  override def read(uuid: String): Iterator[InputStream] = {
    Try(Files.newDirectoryStream(directoryPath.toPath)) match {
      case Failure(f) => throw f;
      case Success(s) =>
        try {
          closeInCaseOfFailure(
            s.asScala
              .filter(!_.toFile.isDirectory)
              .filter(_.toString.contains(uuid))
              .map(f => Try(new FileInputStream(f.toFile)))
              .toList
          ).iterator
        } finally {
          IOUtils.closeQuietly(s)
        }
    }
  }

  def getOutputStream(groupUuid: String, uuid: String): OutputStream = {
    val file: File = Paths.get(directoryPath.getPath(), fileName(groupUuid, uuid)).toFile
    logger.info(s"Saving file ${file}")
    new FileOutputStream(file)
  }

  override def write(groupUuid: String, uuid: String)(input: InputStream) = {
    val output = Try(getOutputStream(groupUuid, uuid)).toOption
    try {
      output.foreach(s => Try(IOUtils.copyLarge(input, s)))
    } finally {
      IOUtils.closeQuietly(input)
      output.foreach(IOUtils.closeQuietly)
    }
  }
}

class YtlS3FileSystem(val config: OphProperties, val s3client: S3Client)
    extends YtlFileSystem(config) {

  def this(config: OphProperties) =
    this(
      config,
      S3Client
        .builder()
        .region(
          Region.of(
            Option(config.getOrElse("ytl.s3.region", null)).getOrElse(
              throw new RuntimeException(s"S3 region configuration 'ytl.s3.region' is missing!")
            )
          )
        )
        .build()
    )

  val logger: Logger = LoggerFactory.getLogger(getClass)

  val bucket = Option(config.getOrElse("ytl.s3.bucket.name", null)).getOrElse(
    throw new RuntimeException(s"Bucket name configuration 'ytl.s3.bucket.name' is missing!")
  )

  override def read(uuid: String): Iterator[InputStream] = {
    Try(
      closeInCaseOfFailure(
        s3client
          .listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
          .contents()
          .asScala
          .map(_.key())
          .filter(_.contains(uuid))
          .toList
          .map(key =>
            Try(
              s3client.getObject(
                GetObjectRequest
                  .builder()
                  .bucket(bucket)
                  .key(key)
                  .build()
              )
            )
          )
      ).iterator
    ) match {
      case Success(x) => x
      case Failure(t) => logAndThrowS3Exception(t)
    }
  }

  override def write(groupUuid: String, uuid: String)(input: InputStream) = {
    try {
      s3client.putObject(
        PutObjectRequest
          .builder()
          .bucket(bucket)
          .key(fileName(groupUuid, uuid))
          .build(),
        RequestBody.fromInputStream(input, input.available())
      )
    } catch {
      case t: Throwable => logAndThrowS3Exception(t)
    } finally {
      IOUtils.closeQuietly(input)
    }
  }

  private def logAndThrowS3Exception(t: Throwable) = {
    t match {
      case e: AwsServiceException =>
        logger.error(
          s"""Got error from Amazon s3. HTTP status code ${e
            .statusCode()}, AWS Error Code ${e.awsErrorDetails().errorCode()},
           error message ${e.awsErrorDetails().errorMessage()}, request ID ${e.requestId()}""",
          e
        )
      case e: SdkClientException =>
        logger.error(s"""Unable to connect to Amazon s3. Got error message ${e.getMessage}""", e)
      case e => logger.error(s"""Got unexpected exception when connecting Amazon s3""", e)
    }
    throw t
  }
}
