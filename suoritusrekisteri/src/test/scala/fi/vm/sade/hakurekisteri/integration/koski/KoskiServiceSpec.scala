package fi.vm.sade.hakurekisteri.integration.koski

import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.{MockCacheFactory, MockConfig}
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.hakemus.HakemusServiceMock
import fi.vm.sade.hakurekisteri.integration.henkilo.{MockOppijaNumeroRekisteri}
import fi.vm.sade.hakurekisteri.tools.ItPostgres
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.slf4j.LoggerFactory
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.io.BufferedSource

class KoskiServiceSpec
    extends AnyFlatSpec
    with BeforeAndAfterAll
    with Matchers
    with MockitoSugar
    with DispatchSupport
    with LocalhostProperties {

  implicit val formats = org.json4s.DefaultFormats

  override val jsonDir = "src/test/scala/fi/vm/sade/hakurekisteri/integration/koski/json/"
  private implicit val system = ActorSystem("test-jdbc")
  private implicit val ec: ExecutionContext = system.dispatcher
  private val config: MockConfig = new MockConfig

  private val logger = LoggerFactory.getLogger(getClass)

  val koskiDatahandler: KoskiDataHandler = mock[KoskiDataHandler]

  val endPoint = mock[Endpoint]
  val client = new VirkailijaRestClient(
    ServiceConfig(serviceUrl = "https://localhost/koski"),
    aClient = Some(new CapturingAsyncHttpClient(endPoint))
  )
  val koskiService = new KoskiService(
    virkailijaRestClient = client,
    oppijaNumeroRekisteri = MockOppijaNumeroRekisteri,
    hakemusService = new HakemusServiceMock(),
    koskiDataHandler = koskiDatahandler
  ) {

    override def saveKoskiDataWithRetries(
      data: Seq[KoskiHenkiloContainer],
      params: KoskiSuoritusTallennusParams,
      description: String = "",
      retries: Int
    ): Future[KoskiProcessingResults] = {
      logger.info(s"Mocking saveKoskiDataWithRetries for ${data.size} containers, params $params")
      Future.successful(
        KoskiProcessingResults(
          succeededHenkiloOids = data.map(_.henkilö.oid.getOrElse("no-oid-found")).toSet,
          failedHenkiloOids = Set.empty
        )
      )
    }
  }

  override def beforeEach(): Unit = {
    logger.info(s"beforeEach!")
    ItPostgres.reset()
  }

  override def afterAll(): Unit = {
    logger.info(s"afterAll!")
    try {
      Await.result(system.terminate(), 10.seconds)
    } catch {
      case e: Exception => logger.error(s"system.terminate() failed", e)
    }
  }

  def readJsonFile(fileName: String): String = {
    val file: BufferedSource = scala.io.Source.fromFile(jsonDir + fileName)
    val result: String = file.mkString
    file.close()
    result
  }

  it should "successfully create, poll and handle Koski massaluovutus call" in {

    //Queryn luonti
    when(endPoint.request(forPattern(".*koski/api/massaluovutus")))
      .thenReturn(
        (
          200,
          List(),
          readJsonFile("massaluovutus_sure_muuttuneet_query_kesken.json")
        )
      )

    //Pollataan yllä luotua querya
    when(
      endPoint.request(
        forPattern(
          ".*/koski/api/massaluovutus/1bdbcd75-298a-46be-9500-237e1f2626d5"
        )
      )
    )
      .thenReturn(
        (
          200,
          List(),
          readJsonFile(
            "massaluovutus_sure_muuttuneet_query_kesken.json"
          ) //Sisältää yhden valmiin tiedosto-urlia
        ),
        (
          200,
          List(),
          readJsonFile(
            "massaluovutus_sure_muuttuneet_query_valmis.json"
          ) //Sisältää kaksi valmista tiedosto-urlia
        )
      )

    when(
      endPoint.request(
        forPattern(
          ".*/koski/api/massaluovutus/1bdbcd75-298a-46be-9500-237e1f2626d5/0.json"
        )
      )
    )
      .thenReturn(
        (
          200,
          List(),
          readJsonFile("massaluovutus_sure_muuttuneet_resultfile_0.json")
        )
      )

    when(
      endPoint.request(
        forPattern(
          ".*/koski/api/massaluovutus/1bdbcd75-298a-46be-9500-237e1f2626d5/1.json"
        )
      )
    )
      .thenReturn(
        (
          200,
          List(),
          readJsonFile("massaluovutus_sure_muuttuneet_resultfile_1.json")
        )
      )

    val future = koskiService.handleKoskiRefreshForOppijaOids(
      Set("1.2.246.562.24.83121267367"),
      KoskiSuoritusTallennusParams(
        saveLukio = true,
        saveAmmatillinen = true,
        massaluovutusPollWaitMillis = 50
      )
    )
    val finalResult = Await.result(future, 10.seconds)
    finalResult.failedHenkiloOids.size should equal(0)
    finalResult.succeededHenkiloOids.size should equal(20)
  }
}
