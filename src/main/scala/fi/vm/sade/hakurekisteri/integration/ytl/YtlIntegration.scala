package fi.vm.sade.hakurekisteri.integration.ytl

import java.util.UUID
import java.util.concurrent.Executors
import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Oids
import fi.vm.sade.hakurekisteri.integration.hakemus.{FullHakemus, HakemusService, Trigger}
import fi.vm.sade.hakurekisteri.suoritus.{SuoritusQuery, VirallinenSuoritus}
import fi.vm.sade.hakurekisteri.storage.Identified
import scala.concurrent._
import scala.concurrent.duration._
import org.joda.time.LocalDate

class YtlIntegration(ytlHttpClient: YtlHttpFetch,
                     hakemusService: HakemusService,
                     suoritusRekisteri: ActorRef) {

  implicit val ec = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(5))
  implicit val timeout = Timeout(15.seconds)

  def persistSuorituksetForKokelaat(ks: Seq[Kokelas]): Unit = {
    val kokelaat = Option(ks)
    kokelaat foreach { _ foreach { persistSuorituksetForSingleKokelas _ } }
  }

  def persistSuorituksetForSingleKokelas(k: Kokelas): Unit = {
    persistYoSuoritus(k)
    persistLukioSuoritus(k)
  }

  private def persistYoSuoritus(k: Kokelas): Unit = {
    val yoSuoritus = Option(k.yo)

    yoSuoritus foreach ( suoritus => {
      val otherYoSuorituksetFuture = fetchPersistedSuoritukset(suoritus.henkilo, Oids.yotutkintoKomoOid)
      val otherYoSuoritukset = Await.result(otherYoSuorituksetFuture, timeout.duration).asInstanceOf[Seq[Any]]

      if (otherYoSuoritukset.nonEmpty) {
        handleOtherYoSuoritukset(k, suoritus, otherYoSuoritukset)
      } else {
        suoritusRekisteri ! suoritus
      }

    })
  }

  private def persistLukioSuoritus(k: Kokelas): Unit = {
    val lukioSuoritus = k.lukio
    lukioSuoritus foreach { suoritusRekisteri ! _ }
  }

  private def fetchPersistedSuoritukset(henkiloOid: String, komo: String): Future[Any] = {
    suoritusRekisteri ? SuoritusQuery(henkilo = Some(henkiloOid), komo = Some(komo))
  }


  private def handleOtherYoSuoritukset (k: Kokelas, suoritus: VirallinenSuoritus, otherYoSuoritukset: Seq[Any]): Unit = {
    val suoritusBeforeNineties = graduatedBeforeNineties(otherYoSuoritukset).find(vs => vs.henkiloOid == k.oid)
    if (suoritusBeforeNineties.isEmpty) {
      suoritusRekisteri ! suoritus
    } else {
      // New suoritus should not overwrite old suoritus?? TODO: RESOLVE
      // suoritusRekisteri ! suoritusBeforeNineties.head
    }
  }

  private def graduatedBeforeNineties(s: Seq[_]) = s.map {
    case v: VirallinenSuoritus with Identified[_] if v.id.isInstanceOf[UUID] =>
      v.asInstanceOf[VirallinenSuoritus with Identified[UUID]]
  }.filter(s => s.valmistuminen.isBefore(new LocalDate(1990, 1, 1)) && s.tila == "VALMIS" && s.vahvistettu)


  private val updateSingleApplicationSuoritukset = (application: FullHakemus) => {
    if (application.hetu.isDefined && application.personOid.isDefined) {
      val student = Option(ytlHttpClient.fetchOne(application.hetu.get))
      val kokelas = student map { StudentToKokelas.convert(application.personOid.get, _) }
      kokelas foreach { persistSuorituksetForSingleKokelas _ }
    } else {
      // log/throw? invalid application data
    }
  }

  // Trigger YTL data update when applications change
  hakemusService.addTrigger(Trigger(updateSingleApplicationSuoritukset))
}
