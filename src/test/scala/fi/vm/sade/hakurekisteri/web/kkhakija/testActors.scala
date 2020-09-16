package fi.vm.sade.hakurekisteri.web.kkhakija

import java.util.Date

import akka.actor.Actor
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.dates.InFuture
import fi.vm.sade.hakurekisteri.integration.haku.{GetHaku, Haku, HakuNotFoundException, RestHaku}
import fi.vm.sade.hakurekisteri.integration.tarjonta._
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.{Lukuvuosimaksu, LukuvuosimaksuQuery, Maksuntila}
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritysTyyppiQuery}
import fi.vm.sade.utils.slf4j.Logging
import org.joda.time.LocalDate

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MockedHakuActor(haku1: RestHaku) extends Actor {
    override def receive: Actor.Receive = {
      case q: GetHaku if q.oid == "1.3.10" => Future.failed(HakuNotFoundException(s"haku not found with oid ${q.oid}")) pipeTo sender
      case q: GetHaku =>  sender ! Haku(haku1)(InFuture)
    }
  }

  class MockedSuoritusActor(suoritus1: Suoritus) extends Actor {
    override def receive: Actor.Receive = {
      case q: SuoritysTyyppiQuery => sender ! Seq(suoritus1)
    }
  }

  class MockedValintarekisteriActor(personOidWithLukuvuosimaksu: String,
                                    paymentRequiredHakukohdeWithMaksettu: String,
                                    noPaymentRequiredHakukohdeButMaksettu: String) extends Actor with Logging {
    private val mockedMaksus: Seq[Lukuvuosimaksu] = List(
      Lukuvuosimaksu(personOidWithLukuvuosimaksu, paymentRequiredHakukohdeWithMaksettu, Maksuntila.maksettu, "muokkaaja", new Date()),
      Lukuvuosimaksu(personOidWithLukuvuosimaksu, noPaymentRequiredHakukohdeButMaksettu, Maksuntila.maksettu, "muokkaaja2", new LocalDate().minusDays(1).toDate)
    )

    override def receive: Actor.Receive = {
      case message@LukuvuosimaksuQuery(hakukohdeOids, _) if hakukohdeOids.contains(paymentRequiredHakukohdeWithMaksettu) ||
        hakukohdeOids.contains(noPaymentRequiredHakukohdeButMaksettu) =>
        logger.debug(s"MockedValintarekisteriActor got message '$message' that matched $paymentRequiredHakukohdeWithMaksettu : returning $mockedMaksus")
        sender ! mockedMaksus
      case unknown =>
        logger.debug(s"MockedValintarekisteriActor got unknown message '$unknown' , returning empty result")
        sender ! Nil
    }
  }
