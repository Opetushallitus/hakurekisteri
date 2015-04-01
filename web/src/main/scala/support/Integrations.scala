package support

import akka.actor.{Props, ActorRef, ActorSystem}
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.haku.HakuActor
import fi.vm.sade.hakurekisteri.integration.koodisto.KoodistoActor
import fi.vm.sade.hakurekisteri.integration.organisaatio.OrganisaatioActor
import fi.vm.sade.hakurekisteri.integration.parametrit.ParameterActor
import fi.vm.sade.hakurekisteri.integration.tarjonta.TarjontaActor
import fi.vm.sade.hakurekisteri.integration.valintatulos.ValintaTulosActor
import fi.vm.sade.hakurekisteri.integration.virta.{VirtaActor, VirtaClient, VirtaConfig, VirtaQueue}
import fi.vm.sade.hakurekisteri.integration.ytl.{YTLConfig, YtlActor}
import fi.vm.sade.hakurekisteri.integration.{ExecutorUtil, ServiceConfig, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.rest.support.Registers
import fi.vm.sade.hakurekisteri.suoritus.VapaamuotoinenKkTutkinto

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
}

class BaseIntegrations(virtaConfig: VirtaConfig,
                       henkiloConfig: ServiceConfig,
                       tarjontaConfig: ServiceConfig,
                       organisaatioConfig: ServiceConfig,
                       parameterConfig: ServiceConfig,
                       hakemusConfig: HakemusConfig,
                       ytlConfig: Option[YTLConfig],
                       koodistoConfig: ServiceConfig,
                       valintaTulosConfig: ServiceConfig,
                       rekisterit: Registers,
                       system: ActorSystem) extends Integrations {

  val ec: ExecutionContext = ExecutorUtil.createExecutor(10, "rest-client-pool")

  val tarjonta = system.actorOf(Props(new TarjontaActor(new VirkailijaRestClient(tarjontaConfig, None)(ec, system))), "tarjonta")

  val organisaatiot = system.actorOf(Props(new OrganisaatioActor(new VirkailijaRestClient(organisaatioConfig, None)(ec, system))), "organisaatio")

  val henkilo = system.actorOf(Props(new fi.vm.sade.hakurekisteri.integration.henkilo.HenkiloActor(new VirkailijaRestClient(henkiloConfig, None)(ec, system))), "henkilo")

  val hakemukset = system.actorOf(Props(new HakemusActor(new VirkailijaRestClient(hakemusConfig.serviceConf, None)(ec, system), hakemusConfig.maxApplications)), "hakemus")

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

  val ytl = system.actorOf(Props(new YtlActor(henkilo, rekisterit.suoritusRekisteri: ActorRef, rekisterit.arvosanaRekisteri: ActorRef, hakemukset, ytlConfig)), "ytl")

  val koodisto = system.actorOf(Props(new KoodistoActor(new VirkailijaRestClient(koodistoConfig, None)(ec, system))), "koodisto")

  val parametrit = system.actorOf(Props(new ParameterActor(new VirkailijaRestClient(parameterConfig, None)(ec, system))), "parametrit")

  val valintaTulos = system.actorOf(Props(new ValintaTulosActor(new VirkailijaRestClient(valintaTulosConfig, None)(ExecutorUtil.createExecutor(5, "valinta-tulos-client-pool"), system))), "valintaTulos")

  val haut = system.actorOf(Props(new HakuActor(tarjonta, parametrit, hakemukset, valintaTulos, ytl)))

  val virta = system.actorOf(Props(new VirtaActor(new VirtaClient(virtaConfig)(system), organisaatiot, rekisterit.suoritusRekisteri, rekisterit.opiskeluoikeusRekisteri)), "virta")

  val virtaQueue = system.actorOf(Props(new VirtaQueue(virta, hakemukset, haut)), "virta-queue")
}
