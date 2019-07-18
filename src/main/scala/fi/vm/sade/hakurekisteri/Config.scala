package fi.vm.sade.hakurekisteri

import java.io.InputStream
import java.nio.file.{Files, Path, Paths}
import java.util.Properties

import akka.actor.ActorSystem
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.integration.hakemus.HakemusConfig
import fi.vm.sade.hakurekisteri.integration.virta.VirtaConfig
import fi.vm.sade.hakurekisteri.integration.{OphUrlProperties, ServiceConfig}
import fi.vm.sade.hakurekisteri.web.rest.support.Security
import org.joda.time.LocalTime
import org.slf4j.LoggerFactory
import support.{Integrations, SureDbLoggingConfig}

import scala.concurrent.duration._
import scala.util.{Success, Try}

object Config {
  def fromString(profile: String) = profile match {
    case "default" => new DefaultConfig
  }
  lazy val globalConfig = fromString(sys.props.getOrElse("hakurekisteri.profile", "default"))
  lazy val mockDevConfig = new MockDevConfig

  val callerId: String = s"${OrganisaatioOids.oph}.suoritusrekisteri.backend"
}

object OrganisaatioOids {
  val oph = "1.2.246.562.10.00000000001"
  val ytl = "1.2.246.562.10.43628088406"
  val csc = "1.2.246.562.10.2013112012294919827487"
  val tuntematon = "1.2.246.562.10.57118763579"
}

object PohjakoulutusOids {
  val luokka = "luokka"
  val perusopetus = "1.2.246.562.13.62959769647"
  val perusopetuksenOppiaineenOppimaara = "TODO perusopetuksenOppiaineenOppimäärä"
  val lukio = "TODO lukio komo oid"
  val ammatillinen = "TODO ammatillinen komo oid"
  val ulkomainen = "1.2.246.562.13.86722481404"
  val yoTutkinto = "1.2.246.562.5.2013061010184237348007"
}

object LisapistekoulutusOids {
  val kymppi = "1.2.246.562.5.2013112814572435044876"
  val talous = "1.2.246.562.5.2013061010184614853416"
  val ammattistartti = "1.2.246.562.5.2013112814572438136372"
  val kansanopisto = "kansanopisto"
  val maahanmuuttajienLukioonValmistava = "1.2.246.562.5.2013112814572429142840"
  val maahanmuuttajienAmmatilliseenValmistava = "1.2.246.562.5.2013112814572441001730"
  val valmentavaJaKuntouttava = "1.2.246.562.5.2013112814572435755085"
  val valma = "valma"
  val telma = "telma"
}

object KomoOids {
  val pohjakoulutus = PohjakoulutusOids
  val ammatillisenKielikoe = "ammatillisenKielikoe"
  val lisapistekoulutus = LisapistekoulutusOids
  val ammatillinentutkinto = "ammatillinentutkinto komo oid"
  val erikoisammattitutkintoKomoOid = "erikoisammattitutkinto komo oid"
  val toisenAsteenVirkailijanKoulutukset = Set(ammatillinentutkinto,erikoisammattitutkintoKomoOid,
    pohjakoulutus.perusopetus, pohjakoulutus.lukio, pohjakoulutus.ammatillinen, pohjakoulutus.ulkomainen,
    lisapistekoulutus.kymppi, lisapistekoulutus.talous, lisapistekoulutus.ammattistartti, lisapistekoulutus.kansanopisto,
    lisapistekoulutus.maahanmuuttajienLukioonValmistava, lisapistekoulutus.maahanmuuttajienAmmatilliseenValmistava,
    lisapistekoulutus.valmentavaJaKuntouttava, lisapistekoulutus.valma, lisapistekoulutus.telma)
}

object Oids {
  val ophOrganisaatioOid = OrganisaatioOids.oph
  val ytlOrganisaatioOid = OrganisaatioOids.ytl
  val cscOrganisaatioOid = OrganisaatioOids.csc
  val tuntematonOrganisaatioOid = OrganisaatioOids.tuntematon

  val perusopetusLuokkaKomoOid = KomoOids.pohjakoulutus.luokka
  val yotutkintoKomoOid = KomoOids.pohjakoulutus.yoTutkinto
  val perusopetusKomoOid = KomoOids.pohjakoulutus.perusopetus
  val ulkomainenkorvaavaKomoOid = KomoOids.pohjakoulutus.ulkomainen
  val lukioKomoOid = KomoOids.pohjakoulutus.lukio
  val ammatillinenKomoOid = KomoOids.pohjakoulutus.ammatillinen
  val ammatillinentutkintoKomoOid = KomoOids.ammatillinentutkinto
  val erikoisammattitutkintoKomoOid = KomoOids.erikoisammattitutkintoKomoOid
  val perusopetuksenOppiaineenOppimaaraOid = KomoOids.pohjakoulutus.perusopetuksenOppiaineenOppimaara

  val lisaopetusKomoOid = KomoOids.lisapistekoulutus.kymppi
  val lisaopetusTalousKomoOid = KomoOids.lisapistekoulutus.talous
  val ammattistarttiKomoOid = KomoOids.lisapistekoulutus.ammattistartti
  val valmentavaKomoOid = KomoOids.lisapistekoulutus.valmentavaJaKuntouttava
  val ammatilliseenvalmistavaKomoOid = KomoOids.lisapistekoulutus.maahanmuuttajienAmmatilliseenValmistava
  val lukioonvalmistavaKomoOid = KomoOids.lisapistekoulutus.maahanmuuttajienLukioonValmistava
  val kansanopistoKomoOid = KomoOids.lisapistekoulutus.kansanopisto
  val valmaKomoOid = KomoOids.lisapistekoulutus.valma
  val telmaKomoOid = KomoOids.lisapistekoulutus.telma
  val DUMMYOID = "999999" //Dummy oid value for to-be-ignored komos
}

class DefaultConfig extends Config {
  def mockMode = false
  log.info("Using default config")
  override val databaseUrl = getPropertyOrCrash("suoritusrekisteri.db.url", "configuration key missing: suoritusrekisteri.db.url")
  override val databaseHost = getPropertyOrCrash("suoritusrekisteri.db.host", "configuration key missing: suoritusrekisteri.db.host")
  override val databasePort = getPropertyOrCrash("suoritusrekisteri.db.port", "configuration key missing: suoritusrekisteri.db.port")
  override val postgresUser = properties.getOrElse("suoritusrekisteri.db.user", "postgres")
  override val postgresPassword = properties.getOrElse("suoritusrekisteri.db.password", "postgres")
  override val archiveNonCurrentAfterDays = properties.getOrElse("suoritusrekisteri.db.archiveNonCurrentAfterDays", "180")
  override val archiveTotalTimeoutMinutes = properties.getOrElse("suoritusrekisteri.db.archiveTotalTimeoutMinutes", "180")
  override val archiveBatchSize = properties.getOrElse("suoritusrekisteri.db.archiveBatchSize" ,"100000")
  override val slowQuery: Long = java.lang.Long.parseLong(getPropertyOrCrash("suoritusrekisteri.db.slowquery.millis", "configuration key missing: suoritusrekisteri.db.slowquery.millis"))
  override val reallySlowQuery: Long = java.lang.Long.parseLong(getPropertyOrCrash("suoritusrekisteri.db.slowquery.millis", "configuration key missing: suoritusrekisteri.db.reallyslowquery.millis"))
  override val maxDbLogLineLength: Int = java.lang.Integer.parseInt(getPropertyOrCrash("suoritusrekisteri.db.max.log.line.length", "configuration key missing: suoritusrekisteri.db.max.log.line.length"))
  private lazy val homeDir = sys.props.getOrElse("user.home", "")
  lazy val ophConfDir: Path = Paths.get(homeDir, "/oph-configuration/")
  override val valintaTulosTimeout: FiniteDuration = java.lang.Integer.parseInt(getPropertyOrCrash("suoritusrekisteri.valintatulos.max.minutes", "configuration key missing: suoritusrekisteri.valintatulos.max.minutes")).minutes
  override val ytlSyncTimeout = Timeout(properties.getOrElse("suoritusrekisteri.ytl.sync.timeout.seconds", "10").toLong, SECONDS)
}

class MockDevConfig extends Config {
  def mockMode = true
  log.info("Using mock dev config")
  override val databaseUrl = properties.getOrElse("suoritusrekisteri.db.url", "jdbc:postgresql://localhost:5432/suoritusrekisteri")
  override val databaseHost = properties.getOrElse("suoritusrekisteri.db.host", "localhost")
  override val databasePort = properties.getOrElse("suoritusrekisteri.db.port", "5432")
  override val postgresUser = properties.getOrElse("suoritusrekisteri.db.user", "postgres")
  override val postgresPassword = properties.getOrElse("suoritusrekisteri.db.password", "postgres")
  override val archiveNonCurrentAfterDays = properties.getOrElse("suoritusrekisteri.db.archiveNonCurrentAfterDays", "14")
  override val archiveTotalTimeoutMinutes = properties.getOrElse("suoritusrekisteri.db.archiveTotalTimeoutMinutes", "180")
  override val archiveBatchSize = properties.getOrElse("suoritusrekisteri.db.archiveBatchSize", "14")


  private val defaultDbLoggingConfig = SureDbLoggingConfig()
  override val slowQuery: Long = defaultDbLoggingConfig.slowQueryMillis
  override val reallySlowQuery: Long = defaultDbLoggingConfig.reallySlowQueryMillis
  override val maxDbLogLineLength: Int = defaultDbLoggingConfig.maxLogLineLength
  override val valintaTulosTimeout: FiniteDuration = 1.minute

  override val ytlSyncTimeout: Timeout = Timeout(4, SECONDS)

  override val importBatchProcessingInitialDelay = 1.seconds
  lazy val ophConfDir = Paths.get(ProjectRootFinder.findProjectRoot().getAbsolutePath, "src/test/resources/oph-configuration")
}

class ProductionServerConfig(val integrations: Integrations, val system: ActorSystem, val security: Security)

abstract class Config {
  def mockMode: Boolean

  val databaseUrl: String
  val databaseHost: String
  val databasePort: String
  val postgresUser: String
  val postgresPassword: String
  val archiveNonCurrentAfterDays: String
  val archiveTotalTimeoutMinutes: String = "180"
  val archiveBatchSize: String

  val slowQuery: Long
  val reallySlowQuery: Long
  val maxDbLogLineLength: Int

  val valintaTulosTimeout: FiniteDuration

  val ytlSyncTimeout: Timeout

  val log = LoggerFactory.getLogger(getClass)
  def ophConfDir: Path

  val propertyLocations = Seq("common.properties")
  val importBatchProcessingInitialDelay = 20.minutes

  // by default the service urls point to QA
  val hostQa = "testi.virkailija.opintopolku.fi"

  lazy val resources = propertyLocations.map(ophConfDir.resolve(_))

  log.info(s"lazy loading properties from paths $resources")

  lazy val properties: Map[String, String] = {
    val propertyFiles = resources.map(f => {
      val t = Try(Files.newInputStream(f))
      if (t.isFailure) log.error("could not load property file", t.failed.get)
      t
    }).collect {
      case Success(is) => is
    }
    loadProperties(propertyFiles)
  }

  val integrations = new IntegrationConfig(hostQa, properties)
  val email: EmailConfig = new EmailConfig(properties)

  OphUrlProperties.defaults.put("baseUrl", properties.getOrElse("host.ilb", "https://" + hostQa))

  val tiedonsiirtoStorageDir = properties.getOrElse("suoritusrekisteri.tiedonsiirto.storage.dir", System.getProperty("java.io.tmpdir"))
  val maxPersonOidCountForHakemusBasedPermissionCheck: Int =
    java.lang.Integer.parseInt(getPropertyOrCrash("suoritusrekisteri.hakemuspermissioncheck.max.personoids",
      "configuration key missing: suoritusrekisteri.hakemuspermissioncheck.max.personoids"))

  def loadProperties(resources: Seq[InputStream]): Map[String, String] = {
    import scala.collection.JavaConverters._
    val rawMap = resources.map((reader) => {val prop = new java.util.Properties; prop.load(reader); Map(prop.asScala.toList: _*)}).foldLeft(Map[String, String]())(_ ++ _)

    resolve(rawMap)
  }

  def getPropertyOrCrash(property: String, errorMsg: String) = {
    properties.getOrElse(property, throw new RuntimeException(errorMsg))
  }

  def resolve(source: Map[String, String]): Map[String, String] = {
    val converted = source.mapValues(_.replace("${","€{"))
    val unResolved = Set(converted.map((s) => (for (found <- "€\\{(.*?)\\}".r findAllMatchIn s._2) yield found.group(1)).toList).foldLeft(List[String]())(_ ++ _):_*)
    val unResolvable = unResolved.filter((s) => converted.get(s).isEmpty)
    if ((unResolved -- unResolvable).isEmpty)
      converted.mapValues(_.replace("€{","${"))
    else
      resolve(converted.mapValues((s) => "€\\{(.*?)\\}".r replaceAllIn (s, m => {converted.getOrElse(m.group(1), "€{" + m.group(1) + "}") })))
  }

  // nasty hack for exposing production server internals to SuoritusrekisteriMocksBootstrap
  var productionServerConfig: ProductionServerConfig = null
}

class IntegrationConfig(hostQa: String, properties: Map[String, String]) {
  val casUrlQa = s"https://$hostQa/cas"
  val organisaatioServiceUrlQa = s"https://$hostQa/organisaatio-service"
  val hakuappServiceUrlQa = s"https://$hostQa/haku-app"
  val ataruUrlQa = s"https://$hostQa/lomake-editori"
  val koodistoServiceUrlQa = s"https://$hostQa/koodisto-service"
  val parameterServiceUrlQa = s"https://$hostQa/ohjausparametrit-service"
  val valintaTulosServiceUrlQa = s"https://$hostQa/valinta-tulos-service"
  val koskiServiceUrlQa = s"https://$hostQa/koski"

  val sijoitteluServiceUrlQa = s"https://$hostQa/sijoittelu-service"
  val tarjontaServiceUrlQa = s"https://$hostQa/tarjonta-service"
  val oppijaNumeroRekisteriServiceUrlQa = s"https://$hostQa/oppijanumerorekisteri-service"

  val virtaServiceUrlTest = "http://virtawstesti.csc.fi/luku/OpiskelijanTiedot"
  val virtaJarjestelmaTest = ""
  val virtaTunnusTest = ""
  val virtaAvainTest = "salaisuus"

  val casUrl = Some(properties.getOrElse("web.url.cas", casUrlQa))
  val tarjontaServiceUrl = properties.getOrElse("cas.service.tarjonta-service", tarjontaServiceUrlQa)
  val koosteServiceUrl = properties("cas.service.valintalaskentakoostepalvelu")
  val hakuappServiceUrl = properties.getOrElse("cas.service.haku-service", hakuappServiceUrlQa)
  val ataruUrl = properties.getOrElse("cas.service.ataru", ataruUrlQa)
  val koodistoServiceUrl = properties.getOrElse("cas.service.koodisto-service", koodistoServiceUrlQa)
  val parameterServiceUrl = properties.getOrElse("cas.service.ohjausparametrit-service", parameterServiceUrlQa)
  val organisaatioServiceUrl = properties.getOrElse("cas.service.organisaatio-service", organisaatioServiceUrlQa)
  val valintaTulosServiceUrl = properties.getOrElse("cas.service.valintatulos-service", valintaTulosServiceUrlQa)
  val oppijaNumeroRekisteriUrl = properties.getOrElse("cas.service.oppijanumerorekisteri-service", oppijaNumeroRekisteriServiceUrlQa)
  val koskiServiceUrl = properties.getOrElse("cas.service.koski-service", koskiServiceUrlQa)
  val maxApplications = properties.getOrElse("suoritusrekisteri.hakijat.max.applications", "2000").toInt
  val virtaServiceUrl = properties.getOrElse("suoritusrekisteri.virta.service.url", virtaServiceUrlTest)
  val virtaJarjestelma = properties.getOrElse("suoritusrekisteri.virta.jarjestelma", virtaJarjestelmaTest)
  val virtaTunnus = properties.getOrElse("suoritusrekisteri.virta.tunnus", virtaTunnusTest)
  val virtaAvain = properties.getOrElse("suoritusrekisteri.virta.avain", virtaAvainTest)

  val hakuappPageSize: Int = properties.getOrElse("suoritusrekisteri.haku-app.pagesize", "200").toInt

  val serviceUser = properties.get("suoritusrekisteri.app.username")
  val servicePassword = properties.get("suoritusrekisteri.app.password")

  val koskiCronJob = findMandatoryPropertyValue("suoritusrekisteri.koski.update.cronJob").toString
  val koskiMaxOppijatPostSize = findMandatoryPropertyValue("suoritusrekisteri.koski.max.oppijat.post.size").toInt
  val koskiMaxOppijatBatchSize = findMandatoryPropertyValue("suoritusrekisteri.koski.max.oppijat.batch.size").toInt

  val oppijaNumeroRekisteriMaxOppijatBatchSize = findMandatoryPropertyValue("suoritusrekisteri.oppijanumerorekisteri-service.max.oppijat.batch.size").toInt

  val virtaConfig = VirtaConfig(virtaServiceUrl, virtaJarjestelma, virtaTunnus, virtaAvain, properties)
  val parameterConfig = ServiceConfig(serviceUrl = parameterServiceUrl,
    properties = properties,
    maxSimultaneousConnections = findMandatoryPropertyValue("suoritusrekisteri.ohjausparametrit-service.max-connections").toInt,
    maxConnectionQueueMs = findMandatoryPropertyValue("suoritusrekisteri.ohjausparametrit-service.max-connection-queue-ms").toInt)
  val hakemusConfig = HakemusConfig(ServiceConfig(casUrl,
      hakuappServiceUrl,
      serviceUser,
      servicePassword,
      properties,
      maxSimultaneousConnections = findMandatoryPropertyValue("suoritusrekisteri.haku-app.max-connections").toInt,
      maxConnectionQueueMs = findMandatoryPropertyValue("suoritusrekisteri.haku-app.max-connection-queue-ms").toInt),
    maxApplications)
  val ataruConfig = ServiceConfig(casUrl,
    ataruUrl,
    serviceUser,
    servicePassword,
    properties,
    maxSimultaneousConnections = findMandatoryPropertyValue("suoritusrekisteri.ataru.max-connections").toInt,
    maxConnectionQueueMs = findMandatoryPropertyValue("suoritusrekisteri.ataru.max-connection-queue-ms").toInt)
  val koosteConfig = ServiceConfig(casUrl,
    koosteServiceUrl,
    serviceUser,
    servicePassword,
    properties,
    maxSimultaneousConnections = findMandatoryPropertyValue("suoritusrekisteri.valintalaskentakoostepalvelu.max-connections").toInt,
    maxConnectionQueueMs = findMandatoryPropertyValue("suoritusrekisteri.valintalaskentakoostepalvelu.max-connection-queue-ms").toInt)
  val tarjontaConfig = ServiceConfig(serviceUrl = tarjontaServiceUrl,
    properties = properties,
    maxSimultaneousConnections = findMandatoryPropertyValue("suoritusrekisteri.tarjonta-service.max-connections").toInt,
    maxConnectionQueueMs = findMandatoryPropertyValue("suoritusrekisteri.tarjonta-service.max-connection-queue-ms").toInt)
  val koodistoConfig = ServiceConfig(serviceUrl = koodistoServiceUrl,
    properties = properties,
    maxSimultaneousConnections = findMandatoryPropertyValue("suoritusrekisteri.koodisto-service.max-connections").toInt,
    maxConnectionQueueMs = findMandatoryPropertyValue("suoritusrekisteri.koodisto-service.max-connection-queue-ms").toInt)
  val organisaatioConfig = ServiceConfig(serviceUrl = organisaatioServiceUrl,
    properties = properties,
    maxSimultaneousConnections = findMandatoryPropertyValue("suoritusrekisteri.organisaatio-service.max-connections").toInt,
    maxConnectionQueueMs = findMandatoryPropertyValue("suoritusrekisteri.organisaatio-service.max-connection-queue-ms").toInt)

  val koskiConfig = ServiceConfig(serviceUrl = koskiServiceUrl,
    user = serviceUser,
    password = servicePassword,
    properties = properties,
    maxSimultaneousConnections = findMandatoryPropertyValue("suoritusrekisteri.koski.max-connections").toInt,
    maxConnectionQueueMs = findMandatoryPropertyValue("suoritusrekisteri.koski.max-connection-queue-ms").toInt)
  val valintaTulosConfig = new ServiceConfig(serviceUrl = valintaTulosServiceUrl,
    properties = properties,
    maxSimultaneousConnections = findMandatoryPropertyValue("suoritusrekisteri.valinta-tulos-service.max-connections").toInt,
    maxConnectionQueueMs = findMandatoryPropertyValue("suoritusrekisteri.valinta-tulos-service.max-connection-queue-ms").toInt) {
    override val httpClientRequestTimeout: Int = 1.hours.toMillis.toInt
  }
  val valintarekisteriConfig = ServiceConfig(serviceUrl = valintaTulosServiceUrl,
    properties = properties,
    maxSimultaneousConnections = findMandatoryPropertyValue("suoritusrekisteri.valinta-tulos-service.max-connections").toInt,
    maxConnectionQueueMs = findMandatoryPropertyValue("suoritusrekisteri.valinta-tulos-service.max-connection-queue-ms").toInt)
  val oppijaNumeroRekisteriConfig = ServiceConfig(casUrl = casUrl,
    serviceUrl = oppijaNumeroRekisteriUrl,
    user = serviceUser,
    password = servicePassword,
    properties = properties,
    maxSimultaneousConnections = findMandatoryPropertyValue("suoritusrekisteri.oppijanumerorekisteri-service.max-connections").toInt,
    maxConnectionQueueMs = findMandatoryPropertyValue("suoritusrekisteri.oppijanumerorekisteri-service.max-connection-queue-ms").toInt)

  val koodistoCacheHours = properties.getOrElse("suoritusrekisteri.cache.hours.koodisto", "12").toInt
  val organisaatioCacheHours = properties.getOrElse("suoritusrekisteri.cache.hours.organisaatio", "12").toInt
  val tarjontaCacheHours = properties.getOrElse("suoritusrekisteri.cache.hours.tarjonta", "12").toInt
  val valintatulosCacheHours = properties.getOrElse("suoritusrekisteri.cache.hours.valintatulos", "4").toInt
  val hakuRefreshTimeHours = properties.getOrElse("suoritusrekisteri.refresh.time.hours.haku", "6").toInt
  val hakemusRefreshTimeHours = properties.getOrElse("suoritusrekisteri.refresh.time.hours.hakemus", "2").toInt
  val valintatulosRefreshTimeHours = properties.getOrElse("suoritusrekisteri.refresh.time.hours.valintatulos", "2").toInt

  val asyncOperationThreadPoolSize: Int = properties.getOrElse("suoritusrekisteri.async.pools.size", "9").toInt

  private def findMandatoryPropertyValue(key: String): String = {
    properties.getOrElse(key, {
      throw new IllegalArgumentException(s"Please add $key to properties.")
    })
  }
}

class EmailConfig(properties: Map[String, String]) {
  val smtpHost: String = properties.getOrElse("smtp.host","")
  val smtpPort: String = properties.getOrElse("smtp.port","25")
  val smtpUseTls: String = properties.getOrElse("smtp.use_tls","false")
  val smtpAuthenticate: String = properties.getOrElse("smtp.authenticate","false")
  val smtpUsername: String = properties.getOrElse("smtp.username","")
  val smtpPassword: String = properties.getOrElse("smtp.password","")
  val smtpSender: String = properties.getOrElse("smtp.sender","noreply@opintopolku.fi")

  def getAsJavaProperties(): Properties = {
    var props = new Properties()
    props.setProperty("mail.smtp.user", smtpUsername)
    props.setProperty("mail.smtp.host", smtpHost)
    props.setProperty("mail.smtp.port", smtpPort)
    props.setProperty("mail.smtp.auth", smtpAuthenticate)
    props.setProperty("mail.smtp.starttls.enable", smtpUseTls)
    props.setProperty("mail.smtp.submitter", smtpSender)
    props
  }
}
