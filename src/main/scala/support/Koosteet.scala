package support

import akka.actor.{Props, ActorRef, ActorSystem}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.ensikertalainen.EnsikertalainenActor
import fi.vm.sade.hakurekisteri.hakija.HakijaActor
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.integration.hakemus.AkkaHakupalvelu
import fi.vm.sade.hakurekisteri.integration.haku.HakuActor
import fi.vm.sade.hakurekisteri.integration.virta.VirtaQueue
import fi.vm.sade.hakurekisteri.rest.support.Registers

import scala.concurrent.ExecutionContext

trait Koosteet {
  val hakijat: ActorRef
  val ensikertalainen: ActorRef
  val haut: ActorRef
  val virtaQueue: ActorRef
}

class BaseKoosteet(system: ActorSystem, integrations: Integrations, registers: Registers, config: Config) extends Koosteet {
  implicit val ec: ExecutionContext = system.dispatcher

  val haut = system.actorOf(Props(new HakuActor(integrations.tarjonta, integrations.parametrit, integrations.hakemukset, integrations.valintaTulos, integrations.ytl, config)), "haut")

  val virtaQueue = system.actorOf(Props(new VirtaQueue(integrations.virta, integrations.hakemukset, haut)), "virta-queue")

  val hakijat = system.actorOf(Props(new HakijaActor(new AkkaHakupalvelu(integrations.hakemusClient, integrations.hakemukset, haut), integrations.organisaatiot, integrations.koodisto, integrations.valintaTulos)), "hakijat")

  override val ensikertalainen: ActorRef = system.actorOf(Props(new EnsikertalainenActor(registers.suoritusRekisteri, registers.opiskeluoikeusRekisteri, integrations.valintarekisteri, integrations.tarjonta, haut, integrations.hakemusService, config)), "ensikertalainen")
}
