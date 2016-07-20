package fi.vm.sade.hakurekisteri.integration

import java.util.UUID

import fi.vm.sade.hakurekisteri.CleanSharedTestJettyBeforeEach
import fi.vm.sade.hakurekisteri.arvosana.{Arvio, Arvosana}
import fi.vm.sade.hakurekisteri.oppija.Todistus
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.suoritus.{VirallinenSuoritus, yksilollistaminen}
import org.joda.time.LocalDate
import org.json4s.jackson.JsonMethods
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class YtlIntegrationSpec extends FlatSpec with Matchers with CleanSharedTestJettyBeforeEach {
  val expectedSuoritus = VirallinenSuoritus(
    henkilo = "1.2.246.562.24.71944845619",
    lahde = "1.2.246.562.10.43628088406",
    vahv = true,
    komo = "1.2.246.562.5.2013061010184237348007",
    myontaja = "1.2.246.562.10.43628088406",
    tila = "VALMIS",
    valmistuminen = new LocalDate(2014, 6, 1),
    yksilollistaminen = yksilollistaminen.Ei,
    suoritusKieli = "FI"
  )

  val expectedSaArvosana = Arvosana(
    suoritus = UUID.randomUUID(),
    arvio = Arvio(
      arvosana = "B",
      asteikko = "YO",
      pisteet = Some(181)
    ),
    aine = "A",
    lisatieto = Some("SA"),
    myonnetty = Some(new LocalDate(2013, 6, 1)),
    valinnainen = true,
    source = "1.2.246.562.10.43628088406",
    lahdeArvot = Map(
      "koetunnus" -> "SA",
      "aineyhdistelmarooli" -> "61"
    ),
    jarjestys = None
  )

  private def waitForArvosanat(): Future[Seq[Todistus]] = {
    implicit val formats = HakurekisteriJsonSupport.format

    Future {
      var result: Option[Seq[Todistus]] = None
      while (result.isEmpty) {
        Thread.sleep(500)
        get("/suoritusrekisteri/rest/v1/oppijat/1.2.246.562.24.71944845619?haku=1.2.3.4") {
          if (response.status == 200) {
            val json = JsonMethods.parse(body)
            if((json \\ "arvosanat").children.nonEmpty) {
              result = (json \\ "suoritukset").extractOpt[Seq[Todistus]]
            }
          } else {
            println(s"error from /suoritusrekisteri/rest/v1/oppijat/1.2.246.562.24.71944845619 ${response.status}: ${response.statusLine}")
          }
        }
      }
      result.get
    }
  }

  it should "insert arvosanat to database with koetunnus and aineyhdistelmarooli fields" in {
    get("/spec/ytl/process/ytl-osakoe-test.xml") {
      response.status should be(202)
    }

    val todistukset = Await.result(waitForArvosanat(), 60.seconds)
    todistukset.size should equal(1)
    todistukset.head.suoritus should equal(expectedSuoritus)
    todistukset.head.arvosanat.size should equal(27)

    val arvosanaSA = todistukset.head.arvosanat.filter(arvosana => {
      arvosana.aine.equals("A") && arvosana.lahdeArvot.get("koetunnus").contains("SA")
    })
    arvosanaSA.length should equal (1)
    arvosanaSA.head should equal (expectedSaArvosana.copy(suoritus = arvosanaSA.head.suoritus))
  }
}
