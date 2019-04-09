import java.util.concurrent.TimeUnit

import _root_.akka.actor.ActorSystem
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.hakija.{Hakija, HakijaQuery}
import fi.vm.sade.hakurekisteri.integration.ExecutorUtil
import fi.vm.sade.hakurekisteri.integration.hakemus.{HakemusServiceMock, Hakupalvelu}
import fi.vm.sade.hakurekisteri.integration.koodisto.KoodistoActorRef
import fi.vm.sade.hakurekisteri.integration.mocks.{HenkiloMock, KoodistoMock, OrganisaatioMock}
import fi.vm.sade.hakurekisteri.integration.tarjonta.TarjontaActorRef
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.ValintarekisteriActorRef
import fi.vm.sade.hakurekisteri.integration.valintatulos.ValintaTulosActorRef
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.jonotus.{AsiakirjaResource, Siirtotiedostojono, SiirtotiedostojonoResource}
import fi.vm.sade.hakurekisteri.web.kkhakija.KkHakijaService
import fi.vm.sade.hakurekisteri.web.proxies._
import javax.servlet.ServletContext
import org.json4s.jackson.JsonMethods._
import org.json4s.{Extraction, _}
import org.scalatra._

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

class SuoritusrekisteriMocksBootstrap extends LifeCycle with HakurekisteriJsonSupport {

  override def init(context: ServletContext) {
    val config = WebAppConfig.getConfig(context)
    implicit val system = config.productionServerConfig.system

    implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(8, getClass.getSimpleName)
    implicit val security = config.productionServerConfig.security

    val anyActorRef = system.deadLetters
    val kkHakijaService = new KkHakijaService(hakemusService = new HakemusServiceMock(),
      hakupalvelu = new Hakupalvelu() {
        override def getHakijat(q: HakijaQuery): Future[Seq[Hakija]] = Future.successful(Seq())
        override def getHakukohdeOids(hakukohderyhma: String, hakuOid: String): Future[Seq[String]] = Future.successful(Seq())
      },
      tarjonta = new TarjontaActorRef(anyActorRef),
      haut = anyActorRef,
      koodisto = new KoodistoActorRef(anyActorRef),
      suoritukset = anyActorRef,
      valintaTulos = new ValintaTulosActorRef(anyActorRef),
      valintaRekisteri = new ValintarekisteriActorRef(anyActorRef),
      Timeout(1, TimeUnit.MINUTES))
    val jono = new Siirtotiedostojono(anyActorRef, kkHakijaService)
    context.mount(new AsiakirjaResource(jono), "/mocks/suoritusrekisteri/asiakirja")
    context.mount(new SiirtotiedostojonoResource(jono), "/mocks/suoritusrekisteri/siirtotiedostojono")
    context.mount(new OrganizationProxyServlet(system), "/organisaatio-service")
    context.mount(new OppijanumerorekisteriProxyServlet(system), "/oppijanumerorekisteri-service")
    context.mount(new KoodistoProxyServlet(system), "/koodisto-service")
    context.mount(new LocalizationMockServlet(system), "/lokalisointi")
    context.mount(new CasMockServlet(system), "/cas")
  }

  class OrganizationProxyServlet(system: ActorSystem) extends OPHProxyServlet(system) {
    get("/rest/organisaatio/v2/ryhmat") {
      new AsyncResult() {
        override val is = Future.successful(OrganisaatioMock.ryhmat())
      }
    }

    get("/rest/organisaatio/v2/hae") {
      new AsyncResult() {
        override val is = Future.successful(OrganisaatioMock.findAll())
      }
    }

    get("/rest/organisaatio/:oid") {
      new AsyncResult() {
        override val is = Future.successful(OrganisaatioMock.findByOid(params("oid")))
      }
    }
  }

  class OppijanumerorekisteriProxyServlet(system: ActorSystem) extends OPHProxyServlet(system) with HakurekisteriJsonSupport {
    get("/cas/prequel") {
      contentType = "text/plain"
      "ok"
    }

    get("/henkilo/:oid") {
      new AsyncResult() {
        override val is = henkiloByOid(params("oid"))
      }
    }

    get("/henkilo/hakutermi=:hakutermi") {
      new AsyncResult() {
        override val is = henkiloByQparam(params("hakutermi"))
      }
    }

    post("/henkilo/henkilotByHenkiloOidList") {
      new AsyncResult() {
        val parsedBody = parse(request.body)
        override val is = henkilotByOidList(parsedBody.extract[List[String]])
      }
    }

    def henkilotByOidList(oidList: List[String]) = Future.successful(HenkiloMock.henkilotByHenkiloOidList(oidList))
    def henkiloByOid(oid: String) = Future.successful(HenkiloMock.getHenkiloByOid(oid))
    def henkiloByQparam(hetu: String) = Future.successful(HenkiloMock.getHenkiloByQParam(hetu))
  }

  class KoodistoProxyServlet(system: ActorSystem)(implicit ec: ExecutionContext) extends OPHProxyServlet(system) with HakurekisteriJsonSupport {
    get("""/rest/json/(.*)""".r) {
      new AsyncResult() {
        val path = multiParams("captures").head
        override val is = koodi(path).map(compact(_))
      }
    }
    val koodit: Map[String, JValue] = Extraction.extract[Map[String, JValue]](parse(KoodistoMock.getKoodisto()))

    def koodi(path: String) = {
      Future.successful(koodit(path.replaceAll("/$", "")))
    }
  }

  class LocalizationMockServlet(system: ActorSystem) extends OPHProxyServlet(system) {
    get("/cxf/rest/v1/localisation") {
      getClass.getResourceAsStream("/proxy-mockdata/localization.json")
    }

    post("/cxf/rest/v1/localisation") {
      Created("{}")
    }
  }

  class CasMockServlet(system: ActorSystem) extends OPHProxyServlet(system) {
    get("/myroles") {
      getClass.getResourceAsStream("/proxy-mockdata/cas-myroles.json")
    }
  }
}
