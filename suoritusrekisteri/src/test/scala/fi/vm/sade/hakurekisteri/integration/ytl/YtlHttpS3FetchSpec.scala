package fi.vm.sade.hakurekisteri.integration.ytl

import java.util.UUID
import java.util.zip.ZipInputStream
import fi.vm.sade.properties.OphProperties
import fi.vm.sade.utils.tcp.PortChecker
import org.scalatra.test.scalatest.ScalatraFunSuite
import org.testcontainers.containers.wait.strategy.DockerHealthcheckWaitStrategy
import org.testcontainers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest

class YtlHttpS3FetchSpec extends ScalatraFunSuite with YtlMockFixture {
  val s3Port = PortChecker.findFreeLocalPort
  val s3Bucket = "ytl-test-bucket"
  val s3Region = Region.EU_WEST_1

  val config = ytlProperties
    .addDefault("ytl.http.buffersize", "128")
    .addOverride("ytl.s3.enabled", "true")
    .addOverride("ytl.s3.bucket.name", s3Bucket)
    .addOverride("ytl.s3.region", Region.EU_WEST_1.toString)

  def ytlHttpFetchWithMockedS3 = {
    val s3TestContainer = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4"))
      .withServices("s3")
      .waitingFor(new DockerHealthcheckWaitStrategy)
    s3TestContainer.start()

    val s3client: S3Client = S3Client
      .builder()
      .region(Region.of(s3TestContainer.getRegion))
      .endpointOverride(s3TestContainer.getEndpoint)
      .credentialsProvider(
        StaticCredentialsProvider.create(
          AwsBasicCredentials.create(
            s3TestContainer.getAccessKey,
            s3TestContainer.getSecretKey
          )
        )
      )
      .build()

    val fileSystem = new YtlS3FileSystem(config, s3client)
    fileSystem.s3client.createBucket(CreateBucketRequest.builder().bucket(s3Bucket).build())
    new YtlHttpFetch(config, fileSystem)
  }

  test("Correct filesystem when s3 enabled") {
    YtlFileSystem(config).isInstanceOf[YtlS3FileSystem] should equal(true)
  }

  test("Fetch many as zip") {
    val groupUuid = UUID.randomUUID().toString
    val students: Iterator[Either[Throwable, (ZipInputStream, Iterator[Student])]] =
      ytlHttpFetchWithMockedS3.fetch(groupUuid, Seq(YtlHetuPostData("050996-9574", None)))

    val (zip, stream) = students.map {
      case Right(x) => x
      case Left(e)  => throw e
    }.next
    stream.size should equal(5)
  }
}
