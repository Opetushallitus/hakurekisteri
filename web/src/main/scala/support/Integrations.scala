package support

import akka.actor.{Actor, Props, ActorRef, ActorSystem}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.Config._
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.haku.HakuActor
import fi.vm.sade.hakurekisteri.integration.koodisto.KoodistoActor
import fi.vm.sade.hakurekisteri.integration.organisaatio.{MockOrganisaatioActor, HttpOrganisaatioActor, OrganisaatioActor}
import fi.vm.sade.hakurekisteri.integration.parametrit.{MockParameterActor, HttpParameterActor, ParameterActor}
import fi.vm.sade.hakurekisteri.integration.tarjonta.TarjontaActor
import fi.vm.sade.hakurekisteri.integration.valintatulos.ValintaTulosActor
import fi.vm.sade.hakurekisteri.integration.virta.{VirtaActor, VirtaClient, VirtaConfig, VirtaQueue}
import fi.vm.sade.hakurekisteri.integration.ytl.{YTLConfig, YtlActor}
import fi.vm.sade.hakurekisteri.integration.{ExecutorUtil, ServiceConfig, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.rest.support.Registers
import fi.vm.sade.hakurekisteri.suoritus.VapaamuotoinenKkTutkinto
import fi.vm.sade.hakurekisteri.web.proxies.{HttpProxies, MockProxies, Proxies}
import scala.concurrent.ExecutionContext
import scala.util.Try

trait Integrations {
  val virta: ActorRef
  val henkilo: ActorRef
  val organisaatiot: ActorRef
  val hakemukset: ActorRef
  val tarjonta: ActorRef
  val koodisto: ActorRef
  val ytl: ActorRef
  val parametrit: ActorRef
  val valintaTulos: ActorRef
  val proxies: Proxies
}

object Integrations {
  def apply(rekisterit: Registers, system: ActorSystem, config: Config) = config.mockMode match {
    case true => new MockIntegrations(system)
    case _ => new BaseIntegrations(rekisterit, system, config)
  }
}

class MockIntegrations(system: ActorSystem) extends Integrations {
  override val virta: ActorRef = mockActor("virta", new DummyActor)
  override val valintaTulos: ActorRef = mockActor("valintaTulos", new DummyActor)
  override val hakemukset: ActorRef = mockActor("hakemukset", new DummyActor)
  override val ytl: ActorRef = mockActor("ytl", new DummyActor)
  override val koodisto: ActorRef = mockActor("koodisto", new DummyActor)
  override val organisaatiot: ActorRef = mockActor("organisaatiot", new MockOrganisaatioActor(config))
  override val parametrit: ActorRef = mockActor("parametrit", new MockParameterActor)
  override val henkilo: ActorRef = mockActor("henkilo", new DummyActor)
  override val tarjonta: ActorRef = mockActor("tarjonta", new DummyActor)
  override val proxies = new MockProxies
  private def mockActor(name: String, actor: => Actor) = system.actorOf(Props(actor), name)
}

class DummyActor extends Actor {
  override def receive: Receive = {
    case x => println("DummyActor: got " + x)
  }
}

class BaseIntegrations(rekisterit: Registers, system: ActorSystem, config: Config) extends Integrations {
  val ec: ExecutionContext = ExecutorUtil.createExecutor(10, "rest-client-pool")

  val tarjonta = system.actorOf(Props(new TarjontaActor(new VirkailijaRestClient(config.integrations.tarjontaConfig, None)(ec, system), config)), "tarjonta")

  private val organisaatioClient = new VirkailijaRestClient(config.integrations.organisaatioConfig, None)(ec, system)
  private val koodistoClient = new VirkailijaRestClient(config.integrations.koodistoConfig, None)(ec, system)
  private val authenticationClient = new VirkailijaRestClient(config.integrations.henkiloConfig, None)(ec, system)

  val organisaatiot = system.actorOf(Props(new HttpOrganisaatioActor(organisaatioClient, config)), "organisaatio")

  val henkilo = system.actorOf(Props(new fi.vm.sade.hakurekisteri.integration.henkilo.HenkiloActor(new VirkailijaRestClient(config.integrations.henkiloConfig, None)(ec, system), config)), "henkilo")

  val hakemukset = system.actorOf(Props(new HakemusActor(new VirkailijaRestClient(config.integrations.hakemusConfig.serviceConf, None)(ec, system), config.integrations.hakemusConfig.maxApplications)), "hakemus")

  val proxies = new HttpProxies(authenticationClient, koodistoClient, organisaatioClient)

  hakemukset ! Trigger {
    (hakemus: FullHakemus) =>
      for (
        person <- hakemus.personOid;
        answers <- hakemus.answers;
        koulutus <- answers.koulutustausta;
        myontaja <- koulutus.aiempitutkinto_korkeakoulu;
        kuvaus <- koulutus.aiempitutkinto_tutkinto;
        vuosiString <- koulutus.aiempitutkinto_vuosi;
        vuosi <- Try(vuosiString.toInt).toOption
      ) rekisterit.suoritusRekisteri ! VapaamuotoinenKkTutkinto(person, kuvaus, myontaja, vuosi, 0, person)
  }

  val ilmoitetutArvosanat = IlmoitetutArvosanatTrigger(rekisterit.suoritusRekisteri,rekisterit.arvosanaRekisteri)(ec);

  hakemukset ! ilmoitetutArvosanat

  val ytl = system.actorOf(Props(new YtlActor(henkilo, rekisterit.suoritusRekisteri: ActorRef, rekisterit.arvosanaRekisteri: ActorRef, hakemukset, config.integrations.ytlConfig)), "ytl")

  val koodisto = system.actorOf(Props(new KoodistoActor(koodistoClient, config)), "koodisto")

  val parametrit = system.actorOf(Props(new HttpParameterActor(new VirkailijaRestClient(config.integrations.parameterConfig, None)(ec, system))), "parametrit")

  val valintaTulos = system.actorOf(Props(new ValintaTulosActor(new VirkailijaRestClient(config.integrations.valintaTulosConfig, None)(ExecutorUtil.createExecutor(5, "valinta-tulos-client-pool"), system), config)), "valintaTulos")

  val virta = system.actorOf(Props(new VirtaActor(new VirtaClient(config.integrations.virtaConfig)(system), organisaatiot, rekisterit.suoritusRekisteri, rekisterit.opiskeluoikeusRekisteri)), "virta")

}
