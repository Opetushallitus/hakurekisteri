package fi.vm.sade.hakurekisteri.integration.ytl

import java.util.UUID

import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, ArvosanaQuery, _}
import fi.vm.sade.hakurekisteri.integration.ActorSystemSupport
import fi.vm.sade.hakurekisteri.integration.hakemus.HakemusServiceMock
import fi.vm.sade.hakurekisteri.integration.henkilo.MockHenkiloActor
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.JDBCJournal
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.storage.repository.Updated
import fi.vm.sade.hakurekisteri.suoritus._
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting
import fi.vm.sade.hakurekisteri.tools.ItPostgres
import fi.vm.sade.hakurekisteri.{KomoOids, MockConfig, OrganisaatioOids}
import org.joda.time.LocalDate
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.language.implicitConversions

class YtlActorUpdateSuoritusSpec extends ScalatraFunSuite with ActorSystemSupport with FutureWaiting {

  val vanhaValmisYoSuoritus = VirallinenSuoritus(
    komo = KomoOids.pohjakoulutus.yoTutkinto,
    myontaja = OrganisaatioOids.ytl,
    tila = "VALMIS",
    valmistuminen = new LocalDate(1989, 6, 1),
    henkilo = "1.2.246.562.24.71944845619",
    yksilollistaminen = yksilollistaminen.Ei,
    suoritusKieli = "FI",
    opiskeluoikeus = None,
    vahv = true,
    lahde = "testivirkailija"
  )


  test("YtlActor should not overwrite an existing old suoritus in tila VALMIS with a new suoritus in tila KESKEN from YTL") {
    withSystem({
      implicit system => {
        implicit val ec: ExecutionContext = system.dispatcher
        implicit val timeout: Timeout = 60.seconds
        implicit val database = Database.forURL(ItPostgres.getEndpointURL())

        val config = new MockConfig

        val henkiloActor = system.actorOf(Props(new MockHenkiloActor(config)))
        val suoritusJournal = new JDBCJournal[Suoritus, UUID, SuoritusTable](TableQuery[SuoritusTable])
        suoritusJournal.addModification(Updated(vanhaValmisYoSuoritus.identify))
        val suoritusActor = system.actorOf(Props(new SuoritusJDBCActor(suoritusJournal, 1)), "suoritukset")
        val arvosanaJournal = new JDBCJournal[Arvosana, UUID, ArvosanaTable](TableQuery[ArvosanaTable])
        val arvosanaActor = system.actorOf(Props(new ArvosanaJDBCActor(arvosanaJournal, 1)), "arvosanat")
        val hakemusService = new HakemusServiceMock

        val ytlActor = system.actorOf(Props(new YtlActor(
          henkiloActor = henkiloActor,
          suoritusRekisteri = suoritusActor,
          arvosanaRekisteri = arvosanaActor,
          hakemusService = hakemusService,
          config = Some(YTLConfig("", "", "", "", "", List(), ""))
        )))

        ytlActor ! YtlResult(UUID.randomUUID(), getClass.getResource("/ytl-update-suoritus-test.xml").getFile)

        val suoritus = Await.result((suoritusActor ? SuoritusQuery(henkilo = Some("1.2.246.562.24.71944845619")))
          .mapTo[Seq[VirallinenSuoritus with Identified[UUID]]], 60.seconds).head

        suoritus.tila should be("VALMIS")
        suoritus.valmistuminen should be(new LocalDate(1989, 6, 1))

        var arvosanat: Option[Seq[Arvosana with Identified[UUID]]] = None
        for (_ <- 1.to(10)) {
          arvosanat = Some(Await.result((arvosanaActor ? ArvosanaQuery(Some(suoritus.id)))
            .mapTo[Seq[Arvosana with Identified[UUID]]], 60.seconds))
          Thread.sleep(100)
        }
        arvosanat.get.nonEmpty should be(true)

        database.close()
      }
    })
  }
}
