package support

import java.util.concurrent.TimeUnit

import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.henkilo.MockHenkiloActor
import fi.vm.sade.hakurekisteri.integration.koodisto.KoodistoActor
import fi.vm.sade.hakurekisteri.integration.organisaatio.{HttpOrganisaatioActor, MockOrganisaatioActor}
import fi.vm.sade.hakurekisteri.integration.parametrit.{HttpParameterActor, MockParameterActor}
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.{ValintarekisteriQuery, ValintarekisteriActor}
import fi.vm.sade.hakurekisteri.integration.{ExecutorUtil, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.tarjonta.{MockTarjontaActor, TarjontaActor}
import fi.vm.sade.hakurekisteri.integration.valintatulos.ValintaTulosActor
import fi.vm.sade.hakurekisteri.integration.virta.{VirtaActor, VirtaClient}
import fi.vm.sade.hakurekisteri.integration.ytl.YtlActor
import fi.vm.sade.hakurekisteri.rest.support.Registers
import fi.vm.sade.hakurekisteri.suoritus.VapaamuotoinenKkTutkinto
import fi.vm.sade.hakurekisteri.web.proxies.{HttpProxies, MockProxies, Proxies}

import scala.concurrent.{ExecutionContextExecutorService, ExecutionContext}
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
  val valintarekisteri: ActorRef
  val proxies: Proxies
}

object Integrations {
  def apply(rekisterit: Registers,
            system: ActorSystem,
            config: Config) = config.mockMode match {
    case true => new MockIntegrations(rekisterit, system, config)
    case _ => new BaseIntegrations(rekisterit, system, config)
  }
}

class MockIntegrations(rekisterit: Registers, system: ActorSystem, config: Config) extends Integrations {
  override val virta: ActorRef = mockActor("virta", new DummyActor)
  override val valintaTulos: ActorRef = mockActor("valintaTulos", new DummyActor)
  override val valintarekisteri: ActorRef = mockActor("valintarekisteri", new Actor {
    override def receive: Receive = {
      case ValintarekisteriQuery(_, _) => sender ! Seq()
      case a => println(s"DummyActor($self): received $a")
    }
  })
  override val hakemukset: ActorRef = mockActor("hakemukset", new MockHakemusActor)
  override val koodisto: ActorRef = mockActor("koodisto", new DummyActor)
  override val organisaatiot: ActorRef = mockActor("organisaatiot", new MockOrganisaatioActor(config))
  override val parametrit: ActorRef = mockActor("parametrit", new MockParameterActor)
  override val henkilo: ActorRef = mockActor("henkilo", new MockHenkiloActor(config))
  override val tarjonta: ActorRef = mockActor("tarjonta", new MockTarjontaActor(config))
  override val ytl: ActorRef = system.actorOf(Props(new YtlActor(henkilo, rekisterit.suoritusRekisteri: ActorRef, rekisterit.arvosanaRekisteri: ActorRef, hakemukset, config.integrations.ytlConfig)), "ytl")
  override val proxies = new MockProxies

  private def mockActor(name: String, actor: => Actor) = system.actorOf(Props(actor), name)
}


class BaseIntegrations(rekisterit: Registers, 
                       system: ActorSystem, 
                       config: Config) extends Integrations {
  val restEc = ExecutorUtil.createExecutor(10, "rest-client-pool")
  val vtsEc = ExecutorUtil.createExecutor(5, "valinta-tulos-client-pool")
  val vrEc = ExecutorUtil.createExecutor(10, "valintarekisteri-client-pool")
  val virtaEc = ExecutorUtil.createExecutor(1, "virta-client-pool")

  system.registerOnTermination(() => {
    restEc.shutdown()
    vtsEc.shutdown()
    vrEc.shutdown()
    virtaEc.shutdown()

    restEc.awaitTermination(3, TimeUnit.SECONDS)
    vtsEc.awaitTermination(3, TimeUnit.SECONDS)
    vrEc.awaitTermination(3, TimeUnit.SECONDS)
    virtaEc.awaitTermination(3, TimeUnit.SECONDS)
  })

  private val tarjontaClient = new VirkailijaRestClient(config.integrations.tarjontaConfig, None)(restEc, system)
  private val organisaatioClient = new VirkailijaRestClient(config.integrations.organisaatioConfig, None)(restEc, system)
  private val koodistoClient = new VirkailijaRestClient(config.integrations.koodistoConfig, None)(restEc, system)
  private val henkiloClient = new VirkailijaRestClient(config.integrations.henkiloConfig, None)(restEc, system)
  private val hakemusClient = new VirkailijaRestClient(config.integrations.hakemusConfig.serviceConf, None)(restEc, system)
  private val parametritClient = new VirkailijaRestClient(config.integrations.parameterConfig, None)(restEc, system)
  private val valintatulosClient = new VirkailijaRestClient(config.integrations.valintaTulosConfig, None)(vtsEc, system)
  private val valintarekisteriClient = new VirkailijaRestClient(config.integrations.valintarekisteriConfig, None)(vrEc, system)

  val tarjonta = system.actorOf(Props(new TarjontaActor(tarjontaClient, config)), "tarjonta")
  val organisaatiot = system.actorOf(Props(new HttpOrganisaatioActor(organisaatioClient, config)), "organisaatio")
  val henkilo = system.actorOf(Props(new fi.vm.sade.hakurekisteri.integration.henkilo.HttpHenkiloActor(henkiloClient, config)), "henkilo")
  val hakemukset = system.actorOf(Props(new HakemusActor(hakemusClient, config.integrations.hakemusConfig.maxApplications)).withDispatcher("akka.hakurekisteri.query-prio-dispatcher"), "hakemus")
  val koodisto = system.actorOf(Props(new KoodistoActor(koodistoClient, config)), "koodisto")
  val parametrit = system.actorOf(Props(new HttpParameterActor(parametritClient)), "parametrit")
  val valintaTulos = system.actorOf(Props(new ValintaTulosActor(valintatulosClient, config)), "valintaTulos")
  val valintarekisteri = system.actorOf(Props(new ValintarekisteriActor(valintarekisteriClient, config)), "valintarekisteri")
  val ytl = system.actorOf(Props(new YtlActor(henkilo, rekisterit.suoritusRekisteri: ActorRef, rekisterit.arvosanaRekisteri: ActorRef, hakemukset, config.integrations.ytlConfig)), "ytl")
  val virta = system.actorOf(Props(new VirtaActor(new VirtaClient(config.integrations.virtaConfig)(virtaEc, system), organisaatiot, rekisterit.suoritusRekisteri, rekisterit.opiskeluoikeusRekisteri)), "virta")
  val proxies = new HttpProxies(henkiloClient, koodistoClient, organisaatioClient)

  hakemukset ! IlmoitetutArvosanatTrigger(rekisterit.suoritusRekisteri, rekisterit.arvosanaRekisteri)(system.dispatcher)

}
