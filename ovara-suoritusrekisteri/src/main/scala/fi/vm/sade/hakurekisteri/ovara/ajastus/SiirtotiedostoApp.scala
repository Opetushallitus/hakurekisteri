package fi.vm.sade.hakurekisteri.ovara.ajastus

import org.slf4j.{Logger, LoggerFactory}
import fi.vm.sade.hakurekisteri
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.ovara.SiirtotiedostoClient

object SiirtotiedostoApp {
  private val logger: Logger = LoggerFactory.getLogger("fi.vm.sade.valintatulosservice.ovara.ajastus.SiirtotiedostoApp")

  def main(args: Array[String]): Unit = {
    logger.info(s"Hello, ovara world!")
    println("wahey!")
    try {
    val config = Config.fromString("default")
    val clientConfig = config.siirtotiedostoClientConfig
    logger.info(s"Using clientConfig: $clientConfig")
    //Todo implement ajastus db logic and instantiate OvaraService etc
    val result = "jee"
      logger.info(s"Operation result: $result")
      //"a"
    } catch {
      case t: Throwable =>
        logger.error(s"Virhe siirtotiedoston muodostamisessa: ${t.getMessage}", t)
    }
  }
}
