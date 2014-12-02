import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.batchimport._
import fi.vm.sade.hakurekisteri.integration.valintatulos.ValintaTulosActor
import fi.vm.sade.hakurekisteri.kkhakija.KkHakijaResource
import fi.vm.sade.hakurekisteri.oppija.OppijaResource
import fi.vm.sade.hakurekisteri.storage.repository.Journal

import akka.camel.CamelExtension
import akka.routing.BroadcastGroup
import fi.vm.sade.hakurekisteri.integration.audit.AuditUri
import fi.vm.sade.hakurekisteri.integration.haku.{HakuResource, HakuActor}
import fi.vm.sade.hakurekisteri.integration.parametrit.ParameterActor
import fi.vm.sade.hakurekisteri.integration.ytl.{YTLConfig, YtlActor}
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.servlet.{Servlet, DispatcherType, ServletContext, ServletContextEvent}

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.arvosana._
import fi.vm.sade.hakurekisteri.ensikertalainen.{EnsikertalainenActor, EnsikertalainenResource}
import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.healthcheck.{HealthcheckActor, HealthcheckResource}
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.tarjonta.TarjontaActor
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.koodisto.KoodistoActor
import fi.vm.sade.hakurekisteri.integration.organisaatio.OrganisaatioActor
import fi.vm.sade.hakurekisteri.integration.virta._
import fi.vm.sade.hakurekisteri.opiskelija._
import fi.vm.sade.hakurekisteri.opiskeluoikeus._
import fi.vm.sade.hakurekisteri.organization.{FutureOrganizationHierarchy, OrganizationHierarchy}
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.suoritus._
import gui.GuiServlet
import org.apache.activemq.camel.component.ActiveMQComponent
import org.joda.time.LocalDate
import org.scalatra.{Handler, LifeCycle}
import org.scalatra.swagger.Swagger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.MutablePropertyValues
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer
import org.springframework.core.io.FileSystemResource
import org.springframework.web.context._
import org.springframework.web.context.support.XmlWebApplicationContext
import org.springframework.web.filter.DelegatingFilterProxy

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.reflect.ClassTag
import HakurekisteriDriver.simple._
import scala.util.Try
import siirto.{PerustiedotKoodisto, Perustiedot, SchemaServlet}


class ScalatraBootstrap extends LifeCycle {
  import Config._
  import AuthorizedRegisters._
  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val system = ActorSystem("hakurekisteri")
  implicit val ec: ExecutionContext = system.dispatcher

  override def init(context: ServletContext) {
    OPHSecurity init context

    val journals = new DbJournals(jndiName)
    val registers = new BareRegisters(system, journals)
    val authorizedRegisters = filter(registers) withAuthorizationDataFrom organisaatioSoapServiceUrl

    //val sanity = system.actorOf(Props(new PerusopetusSanityActor(koodistoServiceUrl, registers.suoritusRekisteri, journals.arvosanaJournal)), "perusopetus-sanity")

    val integrations = new BaseIntegrations(virtaConfig, henkiloConfig, tarjontaConfig, organisaatioConfig, parameterConfig, hakemusConfig, ytlConfig, koodistoConfig, valintaTulosConfig, registers, system)

    val koosteet = new BaseKoosteet(system, integrations, registers)

    val healthcheck = system.actorOf(Props(new HealthcheckActor(authorizedRegisters.arvosanaRekisteri, authorizedRegisters.opiskelijaRekisteri, authorizedRegisters.opiskeluoikeusRekisteri, authorizedRegisters.suoritusRekisteri, integrations.ytl, integrations.hakemukset, koosteet.ensikertalainen, integrations.virtaQueue)), "healthcheck")

    mountServlets(context) (
      ("/", "gui") -> new GuiServlet,
      ("/healthcheck", "healthcheck") -> new HealthcheckResource(healthcheck),
      ("/rest/v1/siirto/perustiedot", "rest/v1/siirto/perustiedot") -> new ImportBatchResource(authorizedRegisters.eraRekisteri, (foo) => ImportBatchQuery(None, None, None))("eranTunniste", "perustiedot", "data", Perustiedot, PerustiedotKoodisto) with SpringSecuritySupport,
      ("/rest/v1/api-docs/*", "rest/v1/api-docs/*") -> new ResourcesApp,
      ("/rest/v1/arvosanat", "rest/v1/arvosanat") -> new HakurekisteriResource[Arvosana, CreateArvosanaCommand](authorizedRegisters.arvosanaRekisteri, ArvosanaQuery(_)) with ArvosanaSwaggerApi with HakurekisteriCrudCommands[Arvosana, CreateArvosanaCommand] with SpringSecuritySupport,
      ("/rest/v1/ensikertalainen", "rest/v1/ensikertalainen") -> new EnsikertalainenResource(koosteet.ensikertalainen),
      ("/rest/v1/haut", "rest/v1/haut") -> new HakuResource(koosteet.haut),
      ("/rest/v1/hakijat", "rest/v1/hakijat") -> new HakijaResource(koosteet.hakijat),
      ("/rest/v1/kkhakijat", "rest/v1/kkhakijat") -> new KkHakijaResource(integrations.hakemukset, integrations.tarjonta, koosteet.haut, integrations.koodisto, registers.suoritusRekisteri, integrations.valintaTulos),
      ("/rest/v1/opiskelijat", "rest/v1/opiskelijat") -> new HakurekisteriResource[Opiskelija, CreateOpiskelijaCommand](authorizedRegisters.opiskelijaRekisteri, OpiskelijaQuery(_)) with OpiskelijaSwaggerApi with HakurekisteriCrudCommands[Opiskelija, CreateOpiskelijaCommand] with SpringSecuritySupport,
      ("/rest/v1/oppijat", "rest/v1/oppijat") -> new OppijaResource(authorizedRegisters, integrations.hakemukset, koosteet.ensikertalainen),
      ("/rest/v1/opiskeluoikeudet", "rest/v1/opiskeluoikeudet") -> new HakurekisteriResource[Opiskeluoikeus, CreateOpiskeluoikeusCommand](authorizedRegisters.opiskeluoikeusRekisteri, OpiskeluoikeusQuery(_)) with OpiskeluoikeusSwaggerApi with HakurekisteriCrudCommands[Opiskeluoikeus, CreateOpiskeluoikeusCommand] with SpringSecuritySupport,
      ("/rest/v1/suoritukset", "rest/v1/suoritukset") -> new HakurekisteriResource[Suoritus, CreateSuoritusCommand](authorizedRegisters.suoritusRekisteri, SuoritusQuery(_)) with SuoritusSwaggerApi with HakurekisteriCrudCommands[Suoritus, CreateSuoritusCommand] with SpringSecuritySupport,
      //("/sanity", "sanity") -> new SanityResource(sanity),
      ("/schemas", "schema") -> new SchemaServlet(Perustiedot, PerustiedotKoodisto),
      //("/upload", "upload") -> new UploadResource(),
      ("/virta", "virta") -> new VirtaResource(integrations.virtaQueue)
    )
  }

  def mountServlets(context: ServletContext)(servlets: ((String, String), Servlet with Handler)*) = {
    implicit val sc = context
    for (((path, name), servlet) <- servlets) context.mount(handler = servlet, urlPattern = path, name = name, loadOnStartup = 1)
  }

  override def destroy(context: ServletContext) {
    system.shutdown()
    system.awaitTermination(15.seconds)
    OPHSecurity.destroy(context)
  }
}

object OPHSecurity extends ContextLoader with LifeCycle {
  val config = OPHConfig(Config.ophConfDir,
    Config.propertyLocations,
    "cas_mode" -> "front",
    "cas_key" -> "suoritusrekisteri",
    "spring_security_default_access" -> "hasRole('ROLE_APP_SUORITUSREKISTERI')",
    "cas_service" -> "${cas.service.suoritusrekisteri}",
    "cas_callback_url" -> "${cas.callback.suoritusrekisteri}"
  )

  val cleanupListener = new ContextCleanupListener

  override def init(context: ServletContext) {
    initWebApplicationContext(context)

    val security = context.addFilter("springSecurityFilterChain", classOf[DelegatingFilterProxy])
    security.addMappingForUrlPatterns(java.util.EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC), true, "/*")
    security.setAsyncSupported(true)
  }

  override def destroy(context: ServletContext) {
    closeWebApplicationContext(context)
    cleanupListener.contextDestroyed(new ServletContextEvent(context))
  }

  override def createWebApplicationContext(sc: ServletContext): WebApplicationContext = {
    config
  }
}

case class OPHConfig(confDir: Path, propertyFiles: Seq[String], props:(String, String)*) extends XmlWebApplicationContext {
  val localProperties = (new java.util.Properties /: Map(props: _*)) {case (newProperties, (k,v)) => newProperties.put(k,v); newProperties}
  setConfigLocation("file:" + confDir + "/security-context-backend.xml")

  val resources: Seq[FileSystemResource] = for (
    fileName <- propertyFiles.reverse
  ) yield new FileSystemResource(confDir.resolve(fileName).toAbsolutePath.toString)

  val placeholder = Bean[PropertySourcesPlaceholderConfigurer](
    "localOverride" -> true,
    "properties" -> localProperties,
    "locations" -> resources.toArray
  )

  object Bean {
    def apply[C](props: (_, _)*)(implicit m: Manifest[C]): BeanDefinition = {
      val definition = new RootBeanDefinition(m.runtimeClass)
      definition.setPropertyValues(new MutablePropertyValues(Map(props: _*).asJava))
      definition
    }
  }

  override def initBeanDefinitionReader(beanDefinitionReader: XmlBeanDefinitionReader) {
    beanDefinitionReader.getRegistry.registerBeanDefinition("propertyPlaceHolder", placeholder)
  }
}

trait Journals {
  val suoritusJournal: Journal[Suoritus, UUID]
  val opiskelijaJournal: Journal[Opiskelija, UUID]
  val opiskeluoikeusJournal: Journal[Opiskeluoikeus, UUID]
  val arvosanaJournal: Journal[Arvosana, UUID]
  val eraJournal: Journal[ImportBatch, UUID]
}

class DbJournals(jndiName: String)(implicit val system: ActorSystem) extends Journals {
  implicit val database = Try(Database.forName(jndiName)).recover {
    case _: javax.naming.NameNotFoundException => Database.forURL("jdbc:h2:file:data/sample", driver = "org.h2.Driver")
  }.get

  override val suoritusJournal = new JDBCJournal[Suoritus, UUID, SuoritusTable](TableQuery[SuoritusTable])
  override val opiskelijaJournal = new JDBCJournal[Opiskelija, UUID, OpiskelijaTable](TableQuery[OpiskelijaTable])
  override val opiskeluoikeusJournal = new JDBCJournal[Opiskeluoikeus, UUID, OpiskeluoikeusTable](TableQuery[OpiskeluoikeusTable])
  override val arvosanaJournal = new JDBCJournal[Arvosana, UUID, ArvosanaTable](TableQuery[ArvosanaTable])
  override val eraJournal = new JDBCJournal[ImportBatch, UUID, ImportBatchTable](TableQuery[ImportBatchTable])

}

class BareRegisters(system: ActorSystem, journals: Journals) extends Registers {
  override val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(journals.suoritusJournal)), "suoritukset")
  override val opiskelijaRekisteri = system.actorOf(Props(new OpiskelijaActor(journals.opiskelijaJournal)), "opiskelijat")
  override val opiskeluoikeusRekisteri = system.actorOf(Props(new OpiskeluoikeusActor(journals.opiskeluoikeusJournal)), "opiskeluoikeudet")
  override val arvosanaRekisteri = system.actorOf(Props(new ArvosanaActor(journals.arvosanaJournal)), "arvosanat")
  override val eraRekisteri: ActorRef = system.actorOf(Props(new ImportBatchActor(journals.eraJournal.asInstanceOf[JDBCJournal[ImportBatch, UUID, ImportBatchTable]], 5)), "erat")
}

class AuthorizedRegisters(organisaatioSoapServiceUrl: String, unauthorized: Registers, system: ActorSystem) extends Registers {
  import akka.pattern.ask
  import scala.reflect.runtime.universe._
  implicit val ec:ExecutionContext = system.dispatcher

  def authorizer[A <: Resource[I, A] : ClassTag: Manifest, I: Manifest](guarded: ActorRef, orgFinder: A => Option[String]): ActorRef = {
    val resource = typeOf[A].typeSymbol.name.toString.toLowerCase
    system.actorOf(Props(new OrganizationHierarchy[A, I](organisaatioSoapServiceUrl, guarded, (i: A) => orgFinder(i).map(Set(_)).getOrElse(Set()))), s"$resource-authorizer")
  }

  val suoritusOrgResolver: PartialFunction[Suoritus, String] = {
    case VirallinenSuoritus(komo: String,
    myontaja: String,
    tila: String,
    valmistuminen: LocalDate,
    henkilo: String,
    yksilollistaminen,
    suoritusKieli: String,
    opiskeluoikeus: Option[UUID],
    vahv: Boolean,
    lahde: String) => myontaja
  }

  val resolve = (arvosana:Arvosana) =>
    unauthorized.suoritusRekisteri.?(arvosana.suoritus)(Timeout(300, TimeUnit.SECONDS)).
      mapTo[Option[Suoritus]].map(
        _.map{
          case (s: VirallinenSuoritus) => Set(s.myontaja, s.source, arvosana.source)
          case (s: VapaamuotoinenSuoritus) => Set(s.source, arvosana.source)
        }.getOrElse(Set()))

  override val suoritusRekisteri = authorizer[Suoritus, UUID](unauthorized.suoritusRekisteri, suoritusOrgResolver.lift)
  override val opiskelijaRekisteri = authorizer[Opiskelija, UUID](unauthorized.opiskelijaRekisteri, (opiskelija) => Some(opiskelija.oppilaitosOid))
  override val opiskeluoikeusRekisteri = authorizer[Opiskeluoikeus, UUID](unauthorized.opiskeluoikeusRekisteri, (opiskeluoikeus) => Some(opiskeluoikeus.myontaja))
  override val arvosanaRekisteri =  system.actorOf(Props(new FutureOrganizationHierarchy[Arvosana, UUID](organisaatioSoapServiceUrl, unauthorized.arvosanaRekisteri,
    resolve
    )), "arvosana-authorizer")
  override val eraRekisteri: ActorRef = authorizer[ImportBatch, UUID](unauthorized.eraRekisteri, (era) => Some(Config.ophOrganisaatioOid))
}

object AuthorizedRegisters {
  def filter(unauthorized: Registers): AuthorizerBuilder = new AuthorizerBuilder(unauthorized)

  class AuthorizerBuilder(registers: Registers) {
    def withAuthorizationDataFrom(url: String)(implicit system: ActorSystem) = new AuthorizedRegisters(url, registers, system)
  }
}

class AuditedRegisters(amqUrl: String, amqQueue: String, authorizedRegisters: Registers, system: ActorSystem) extends Registers {
  val log = LoggerFactory.getLogger(getClass)

  //audit
  val camel = CamelExtension(system)
  val broker = "activemq"
  camel.context.addComponent(broker, ActiveMQComponent.activeMQComponent(amqUrl))
  implicit val audit = AuditUri(broker, amqQueue)

  log.debug(s"AuditLog using uri: $amqUrl")

  val suoritusRekisteri = getBroadcastForLogger[Suoritus, UUID](authorizedRegisters.suoritusRekisteri)
  val opiskelijaRekisteri = getBroadcastForLogger[Opiskelija, UUID](authorizedRegisters.opiskelijaRekisteri)
  val opiskeluoikeusRekisteri = getBroadcastForLogger[Opiskeluoikeus, UUID](authorizedRegisters.opiskeluoikeusRekisteri)
  val arvosanaRekisteri = getBroadcastForLogger[Arvosana, UUID](authorizedRegisters.arvosanaRekisteri)

  import fi.vm.sade.hakurekisteri.integration.audit.AuditLog
  import scala.reflect.runtime.universe._

  def getBroadcastForLogger[A <: Resource[I, A]: TypeTag: ClassTag, I: TypeTag: ClassTag](rekisteri: ActorRef) = {
    val routees = List(rekisteri, system.actorOf(Props(new AuditLog[A, I](typeOf[A].typeSymbol.name.toString)).withDispatcher("akka.hakurekisteri.audit-dispatcher"), typeOf[A].typeSymbol.name.toString.toLowerCase + "-audit"))
    system.actorOf(Props.empty.withRouter(BroadcastGroup(paths = routees map (_.path.toString))))
  }

  override val eraRekisteri: ActorRef = system.deadLetters
}

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

  val tarjonta = system.actorOf(Props(new TarjontaActor(new VirkailijaRestClient(tarjontaConfig, None)( ec, system))), "tarjonta")

  val organisaatiot = system.actorOf(Props(new OrganisaatioActor(new VirkailijaRestClient(organisaatioConfig, None)( ec, system))), "organisaatio")

  val henkilo = system.actorOf(Props(new fi.vm.sade.hakurekisteri.integration.henkilo.HenkiloActor(new VirkailijaRestClient(henkiloConfig, None)( ec, system))), "henkilo")

  val hakemukset = system.actorOf(Props(new HakemusActor(new VirkailijaRestClient(hakemusConfig.serviceConf, None)( ec, system), hakemusConfig.maxApplications)), "hakemus")

  hakemukset ! Trigger{
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

  val virta = system.actorOf(Props(new VirtaActor(new VirtaClient(virtaConfig)(system), organisaatiot, rekisterit.suoritusRekisteri, rekisterit.opiskeluoikeusRekisteri)), "virta")

  val virtaQueue = system.actorOf(Props(new VirtaQueue(virta, hakemukset)), "virta-queue")

  val ytl = system.actorOf(Props(new YtlActor(henkilo, rekisterit.suoritusRekisteri: ActorRef, rekisterit.arvosanaRekisteri: ActorRef, hakemukset, ytlConfig)), "ytl")

  val koodisto = system.actorOf(Props(new KoodistoActor(new VirkailijaRestClient(koodistoConfig, None)( ec, system))), "koodisto")

  val parametrit = system.actorOf(Props(new ParameterActor(new VirkailijaRestClient(parameterConfig, None)( ec, system))), "parametrit")

  val valintaTulos = system.actorOf(Props(new ValintaTulosActor(new VirkailijaRestClient(valintaTulosConfig, None)(ExecutorUtil.createExecutor(5, "valinta-tulos-client-pool"), system))), "valintaTulos")
}

trait Koosteet {
  val hakijat: ActorRef
  val ensikertalainen: ActorRef
  val haut: ActorRef
}

class BaseKoosteet(system: ActorSystem, integrations: Integrations, registers: Registers) extends Koosteet {
  implicit val ec: ExecutionContext = system.dispatcher

  val haut = system.actorOf(Props(new HakuActor(integrations.tarjonta, integrations.parametrit, integrations.hakemukset, integrations.valintaTulos, integrations.ytl)))

  val hakijat = system.actorOf(Props(new HakijaActor(new AkkaHakupalvelu(integrations.hakemukset, haut), integrations.organisaatiot, integrations.koodisto, integrations.valintaTulos)), "hakijat")

  override val ensikertalainen: ActorRef = system.actorOf(Props(new EnsikertalainenActor(registers.suoritusRekisteri, registers.opiskeluoikeusRekisteri, integrations.tarjonta)), "ensikertalainen")
}