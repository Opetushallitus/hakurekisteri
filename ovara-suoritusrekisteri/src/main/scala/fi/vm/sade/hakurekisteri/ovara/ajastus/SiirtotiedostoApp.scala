package fi.vm.sade.hakurekisteri.ovara.ajastus

import akka.actor.ActorSystem
import org.slf4j.{Logger, LoggerFactory}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.ovara.{OvaraDbRepository, OvaraDbRepositoryImpl, OvaraService, SiirtotiedostoClient}
import support.{BareRegisters, BaseKoosteet, DbJournals, Integrations, PersonAliasesProvider}

import scala.concurrent.Future

object SiirtotiedostoApp {
  private val logger: Logger =
    LoggerFactory.getLogger("fi.vm.sade.valintatulosservice.ovara.ajastus.SiirtotiedostoApp")


  def createOvaraService(config: Config, system: ActorSystem) = {
    implicit val actorSystem: ActorSystem = system
    val journals = new DbJournals(config)

    val noAliasesProvider = new PersonAliasesProvider {
      override def enrichWithAliases(henkiloOids: Set[String]): Future[PersonOidsWithAliases] = {
        Future.successful(PersonOidsWithAliases(henkiloOids, Map()))
      }
    }

    //Todo, saadaanko jotenkin helposti käsiin ensikertalaisActor ja hakuActor ilman että initialisoidaan koko himmeli,
    //vrt. ovaraDbRepository. HakuActoria ei tarvita, jos haetaan aktiiviset kk-haut jollain muulla tavalla.
    //val registers =
    //  new BareRegisters(system, journals, journals.database, noAliasesProvider, config)
    //val integrations = Integrations(registers, system, config)

    val ovaraDbRepository: OvaraDbRepository = new OvaraDbRepositoryImpl(journals.database)
    //val koosteet = new BaseKoosteet(system, integrations, registers, config)

    new OvaraService(
      ovaraDbRepository,
      new SiirtotiedostoClient(config.siirtotiedostoClientConfig),
      null, //fixme
      null, //fixme
      config.siirtotiedostoPageSize
    )
  }
  def main(args: Array[String]): Unit = {
    try {
      implicit val system: ActorSystem = ActorSystem("ovara-suoritusrekisteri")

      println("Hello, ovara-suoritusrekisteri world!")

      logger.info(s"Hello, ovara-suoritusrekisteri world!")

      val config = Config.fromString("default")

      val clientConfig = config.siirtotiedostoClientConfig
      logger.info(s"Using clientConfig: $clientConfig")

      val ovaraService = createOvaraService(config, system)

      //Todo implement ajastus db logic and instantiate OvaraService etc
      val result = ovaraService.formSiirtotiedostotPaged(1718312400000L, System.currentTimeMillis())
      logger.info(s"Operation result: $result")
      system.terminate()
    } catch {
      case t: Throwable =>
        println(s"Virhe siirtotiedoston muodostamisessa: ${t.getMessage}")
        logger.error(s"Virhe siirtotiedoston muodostamisessa: ${t.getMessage}", t)
    }
  }
}
