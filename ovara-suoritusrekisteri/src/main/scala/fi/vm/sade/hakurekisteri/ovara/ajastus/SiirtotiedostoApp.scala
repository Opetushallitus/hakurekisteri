package fi.vm.sade.hakurekisteri.ovara.ajastus

import akka.actor.{ActorSystem, Props}
import org.slf4j.{Logger, LoggerFactory}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.ensikertalainen.EnsikertalainenActor
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.ovara.{OvaraService, SiirtotiedostoClient}
import support.{BareRegisters, DbJournals, Integrations, OvaraIntegrations, PersonAliasesProvider}

import scala.concurrent.Future

object SiirtotiedostoApp {
  private val logger: Logger =
    LoggerFactory.getLogger("fi.vm.sade.valintatulosservice.ovara.ajastus.SiirtotiedostoApp")

  def createOvaraService(config: Config, system: ActorSystem) = {
    implicit val actorSystem: ActorSystem = system
    val journals = new DbJournals(config)

    val ovaraIntegrations = new OvaraIntegrations(system, config)

    val personAliasesProvider = new PersonAliasesProvider {
      override def enrichWithAliases(henkiloOids: Set[String]): Future[PersonOidsWithAliases] =
        ovaraIntegrations.oppijaNumeroRekisteri.enrichWithAliases(henkiloOids)
    }

    val registers =
      new BareRegisters(system, journals, journals.database, personAliasesProvider, config)

    val ensikertalainenActor = system.actorOf(
      Props(
        new EnsikertalainenActor(
          registers.suoritusRekisteri,
          registers.opiskeluoikeusRekisteri,
          ovaraIntegrations.valintarekisteri,
          ovaraIntegrations.tarjonta,
          ovaraIntegrations.haut,
          ovaraIntegrations.hakemusService,
          ovaraIntegrations.oppijaNumeroRekisteri,
          config
        )
      ),
      "ovara-ensikertalainen"
    )

    new OvaraService(
      registers.ovaraDbRepository,
      new SiirtotiedostoClient(config.siirtotiedostoClientConfig),
      ensikertalainenActor,
      ovaraIntegrations.haut,
      config.siirtotiedostoPageSize
    )
  }

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("ovara-suoritusrekisteri")
    try {
      logger.info(s"Hello, ovara-suoritusrekisteri world!")

      val config = Config.fromString("default")

      val clientConfig = config.siirtotiedostoClientConfig
      logger.info(s"Using clientConfig: $clientConfig")

      val ovaraService = createOvaraService(config, system)

      //Todo implement ajastus db logic and instantiate OvaraService etc
      ovaraService.muodostaSeuraavaSiirtotiedosto
      system.terminate()
    } catch {
      case t: Throwable =>
        logger.error(s"Siirtotiedoston muodostaminen epäonnistui, lopetetaan: ${t.getMessage}", t)
        system.terminate()
        Thread.sleep(5000)
        System.exit(
          1
        ) //Fixme, juuri nyt tämä on tarpeellinen että suoritus saadaan katki, mutta siistimmin voisi yrittää. Joku service/actor ilmeisesti jää ajoon myös system.terminaten jölkeen.
    }
  }
}
