package fi.vm.sade.hakurekisteri.ovara

import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.valinta.dokumenttipalvelu.SiirtotiedostoPalvelu
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization.{write, writePretty}
import fi.vm.sade.utils.slf4j.Logging

import java.io.ByteArrayInputStream

case class SiirtotiedostoClientConfig(region: String, bucket: String, roleArn: String)

trait SiirtotiedostoClient {
  def saveSiirtotiedosto[T](
    contentType: String,
    content: Seq[T],
    executionId: String,
    fileNumber: Int
  ): Unit
}

class SiirtotiedostoClientImpl(config: SiirtotiedostoClientConfig)
    extends SiirtotiedostoClient
    with Logging {
  logger.info(s"Created SiirtotiedostoClient with config $config")
  lazy val siirtotiedostoPalvelu =
    new SiirtotiedostoPalvelu(config.region, config.bucket, config.roleArn)
  val saveRetryCount = 2

  private implicit val formats = HakurekisteriJsonSupport.format

  def saveSiirtotiedosto[T](
    contentType: String,
    content: Seq[T],
    executionId: String,
    fileNumber: Int
  ): Unit = {
    try {
      if (content.nonEmpty) {
        val output = writePretty(Seq(content.head))
        logger.info(
          s"($executionId) Saving siirtotiedosto... total ${content.length}, first: ${content.head}"
        )
        logger.info(s"($executionId) Saving siirtotiedosto... output: $output")
        siirtotiedostoPalvelu
          .saveSiirtotiedosto(
            "sure",
            contentType,
            "",
            executionId,
            fileNumber,
            new ByteArrayInputStream(write(content).getBytes()),
            saveRetryCount
          )
          .key
      } else {
        logger.info(s"($executionId) Ei tallennettavaa!")
      }
    } catch {
      case t: Throwable =>
        logger.error(s"($executionId) Siirtotiedoston tallennus s3-ämpäriin epäonnistui:", t)
        throw t
    }
  }
}

class MockSiirtotiedostoClient() extends SiirtotiedostoClient with Logging {
  override def saveSiirtotiedosto[T](
    contentType: String,
    content: Seq[T],
    executionId: String,
    fileNumber: Int
  ): Unit = {
    logger.info(
      s"($executionId) Saving siirtotiedosto... total ${content.length}, first: ${content.head}"
    )
  }
}
