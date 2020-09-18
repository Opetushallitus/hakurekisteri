package fi.vm.sade.hakurekisteri.integration.ytl

import java.util.UUID
import java.util.zip.ZipInputStream

import com.amazonaws.auth.{AWSStaticCredentialsProvider, AnonymousAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.amazonaws.services.s3.model.CreateBucketRequest
import fi.vm.sade.properties.OphProperties
import fi.vm.sade.utils.tcp.PortChecker
import io.findify.s3mock.S3Mock
import org.scalatra.test.scalatest.ScalatraFunSuite

class YtlHttpS3FetchSpec extends ScalatraFunSuite with YtlMockFixture {
  val s3Port = PortChecker.findFreeLocalPort
  val s3Bucket = "ytl-test-bucket"
  val s3Region = Regions.EU_WEST_1

  val config = ytlProperties
    .addDefault("ytl.http.buffersize", "128")
    .addOverride("ytl.s3.enabled", "true")
    .addOverride("ytl.s3.bucket.name", s3Bucket)
    .addOverride("ytl.s3.region", Regions.EU_WEST_1.getName)

  def ytlHttpFetchWithMockedS3 = {

    val s3client: AmazonS3 = AmazonS3ClientBuilder.standard
      .withPathStyleAccessEnabled(true)
      .withEndpointConfiguration(
        new EndpointConfiguration("http://localhost:" + s3Port, s3Region.getName)
      )
      .withCredentials(new AWSStaticCredentialsProvider(new AnonymousAWSCredentials()))
      .build

    val api = S3Mock(port = s3Port)
    api.start

    val fileSystem = new YtlS3FileSystem(config, s3client)
    fileSystem.s3client.createBucket(new CreateBucketRequest(s3Bucket, s3Region.getName))
    new YtlHttpFetch(config, fileSystem)
  }

  test("Correct filesystem when s3 enabled") {
    YtlFileSystem(config).isInstanceOf[YtlS3FileSystem] should equal(true)
  }

  test("Fetch many as zip") {
    val groupUuid = UUID.randomUUID().toString
    val students: Iterator[Either[Throwable, (ZipInputStream, Iterator[Student])]] =
      ytlHttpFetchWithMockedS3.fetch(groupUuid, List("050996-9574"))

    val (zip, stream) = students.map {
      case Right(x) => x
      case Left(e)  => throw e
    }.next
    stream.size should equal(5)
  }
}
