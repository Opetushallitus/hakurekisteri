package fi.vm.sade.hakurekisteri.ovara

import fi.vm.sade.valinta.dokumenttipalvelu.SiirtotiedostoPalvelu
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization.{write, writePretty}
import fi.vm.sade.utils.slf4j.Logging

import java.io.ByteArrayInputStream

case class SiirtotiedostoClientConfig(region: String, bucket: String, roleArn: String)

class SiirtotiedostoClient(config: SiirtotiedostoClientConfig) extends Logging {
  logger.info(s"Created SiirtotiedostoClient with config $config")
  lazy val siirtotiedostoPalvelu =
    new SiirtotiedostoPalvelu(config.region, config.bucket, config.roleArn)
  val saveRetryCount = 2

  implicit val formats: Formats = DefaultFormats

  def saveSiirtotiedosto[T](contentType: String, content: Seq[T]): String = {
    try {
      if (content.nonEmpty) {
        val output = writePretty(Seq(content.head))
        logger.info(s"Saving siirtotiedosto... total ${content.length}, first: ${content.head}")
        logger.info(s"Saving siirtotiedosto... output: $output")
        siirtotiedostoPalvelu
          .saveSiirtotiedosto(
            "valintarekisteri",
            contentType,
            "",
            new ByteArrayInputStream(write(content).getBytes()),
            saveRetryCount
          )
          .key
      } else {
        logger.info("Ei tallennettavaa!")
        ""
      }
    } catch {
      case t: Throwable =>
        logger.error(s"Siirtotiedoston tallennus s3-ämpäriin epäonnistui:", t)
        throw t
    }
  }
}
