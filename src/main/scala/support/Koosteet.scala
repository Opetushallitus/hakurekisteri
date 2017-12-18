package support

import akka.actor.{ActorRef, ActorSystem, Props}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.ensikertalainen.EnsikertalainenActor
import fi.vm.sade.hakurekisteri.hakija.HakijaActor
import fi.vm.sade.hakurekisteri.integration.hakemus.AkkaHakupalvelu
import fi.vm.sade.hakurekisteri.integration.haku.HakuActor
import fi.vm.sade.hakurekisteri.integration.virta.VirtaQueue
import fi.vm.sade.hakurekisteri.rest.support.Registers
import fi.vm.sade.hakurekisteri.web.jonotus.Siirtotiedostojono
import fi.vm.sade.hakurekisteri.web.kkhakija.KkHakijaService

import scala.concurrent.ExecutionContext

trait Koosteet {
  val hakijat: ActorRef
  val ensikertalainen: ActorRef
  val haut: ActorRef
  val virtaQueue: ActorRef
  val siirtotiedostojono: Siirtotiedostojono
  val kkHakijaService: KkHakijaService
}

class BaseKoosteet(system: ActorSystem, integrations: Integrations, registers: Registers, config: Config) extends Koosteet {
  implicit val ec: ExecutionContext = system.dispatcher

  val haut = system.actorOf(Props(new HakuActor(integrations.tarjonta, integrations.parametrit, integrations.valintaTulos, integrations.ytl, integrations.ytlIntegration, config)), "haut")

  val virtaQueue = system.actorOf(Props(new VirtaQueue(integrations.virta, integrations.hakemusService, integrations.oppijaNumeroRekisteri, haut)), "virta-queue")
  val hakupalvelu = new AkkaHakupalvelu(integrations.hakemusClient, integrations.hakemusService, integrations.koosteService, haut, integrations.koodisto)
  val hakijat = system.actorOf(Props(new HakijaActor(new AkkaHakupalvelu(integrations.hakemusClient, integrations.hakemusService, integrations.koosteService, haut, integrations.koodisto), integrations.organisaatiot, integrations.koodisto, integrations.valintaTulos)), "hakijat")

  override val ensikertalainen: ActorRef = system.actorOf(Props(new EnsikertalainenActor(registers.suoritusRekisteri, registers.opiskeluoikeusRekisteri, integrations.valintarekisteri, integrations.tarjonta, haut, integrations.hakemusService, integrations.oppijaNumeroRekisteri, config)), "ensikertalainen")

  val kkHakijaService: KkHakijaService = new KkHakijaService(integrations.hakemusService,
    hakupalvelu,
    integrations.tarjonta,
    haut,
    integrations.koodisto,
    registers.suoritusRekisteri,
    integrations.valintaTulos,
    integrations.valintarekisteri)(system)
  val siirtotiedostojono = new Siirtotiedostojono(hakijat, kkHakijaService)(system)
}
