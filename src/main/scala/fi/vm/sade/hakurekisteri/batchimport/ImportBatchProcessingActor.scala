package fi.vm.sade.hakurekisteri.batchimport

import java.util.UUID

import akka.actor.Status.Failure
import akka.actor._
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.henkilo._
import fi.vm.sade.hakurekisteri.integration.organisaatio.Organisaatio
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.{yksilollistaminen, VirallinenSuoritus}
import org.joda.time.LocalDate

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.xml.Node

object ProcessReadyBatches

case class ProcessedBatch(batch: ImportBatch)

case class MultipleHenkilosFoundException(message: String, batch: ImportBatch with Identified[UUID]) extends Exception(message)

class ImportBatchProcessingActor(importBatchActor: ActorRef, henkiloActor: ActorRef, suoritusrekisteri: ActorRef, organisaatioActor: ActorRef)(implicit val system: ActorSystem, val ec: ExecutionContext) extends Actor with ActorLogging {
  var processing = false
  var batches: Seq[ImportBatch with Identified[UUID]] = Seq()

  system.scheduler.schedule(5.minutes, 5.minutes, self, ProcessReadyBatches)

  override def receive: Receive = {
    case ProcessReadyBatches if !processing =>
      processing = true
      importBatchActor ! ImportBatchQuery(None, Some(BatchState.READY), None)

    case b: Seq[ImportBatch with Identified[UUID]] =>
      batches = b
      batches.foreach(batch => {
        context.actorOf(Props(new PerustiedotProcessingActor(batch, self, henkiloActor, suoritusrekisteri, organisaatioActor)))
      })

    case ProcessedBatch(b) =>
      importBatchActor ! b.copy(data = b.data, externalId = b.externalId, batchType = b.batchType, source = b.source, state = BatchState.DONE)
      batches = batches.filterNot(_ == b)
      if (batches.length == 0) processing = false

    case Failure(t: MultipleHenkilosFoundException) =>
      log.error(t, s"error processing batch ${t.batch.id}")
  }



  case class HenkiloDone(tunniste: String)

  class PerustiedotProcessingActor(b: ImportBatch with Identified[UUID], parent: ActorRef, henkiloActor: ActorRef, suoritusrekisteri: ActorRef, organisaatioActor: ActorRef)
    extends Actor {

    var henkilot: Map[String, ImportHenkilo] = (b.data \ "henkilot" \ "henkilo").map(ImportHenkilo(_)).groupBy(_.tunniste.tunniste).mapValues(_.head)
    var organisaatiot: Map[String, Option[Organisaatio]] = Map()

    override def preStart(): Unit = {
      henkilot.values.foreach((h: ImportHenkilo) =>
        h.tunniste match {
          case ImportHetu(hetu) => henkiloActor ! HenkiloQuery(None, Some(hetu), h.tunniste.tunniste)
          case ImportOppijanumero(oppijanumero) => henkiloActor ! HenkiloQuery(Some(oppijanumero), None, h.tunniste.tunniste)
          case ImportHenkilonTunniste(_, _, _) => saveHenkilo(h)
        }
      )
    }

    def saveHenkilo(h: ImportHenkilo, henkilo: Option[Henkilo] = None) = henkilo match {
      case Some(henk) => henkiloActor ! h.toUpdatedHenkilo(henk)
      case None => henkiloActor ! h.toHenkilo
    }

    override def receive: Actor.Receive = {
      case resp: FoundHenkilos =>
        val importHenkilo = henkilot(resp.tunniste)
        if (resp.henkilot.length == 0) {
          saveHenkilo(importHenkilo)
        } else if (resp.henkilot.length == 1) {
          saveHenkilo(importHenkilo, resp.henkilot.headOption)
        } else {
          throw MultipleHenkilosFoundException(s"Henkilöpalvelusta löytyi henkilön (${importHenkilo.tunniste}) tiedot monta kertaa. Passivoi duplikaattihenkilöt henkilönhallinnasta ja yritä uudelleen.", b)
        }

      case saved: SavedHenkilo =>
        val importHenkilo = henkilot(saved.tunniste)

        importHenkilo.suoritukset.foreach(s => {
          organisaatiot = organisaatiot + (s.myontaja -> None)
          organisaatioActor ! s.myontaja
        })

        // TODO henkilo tallennettiin henkilopalveluun. rikasta ja tallenna vielä suoritukset.

        // TODO persistoi henkiloTunnistella tuleva uuden henkilön oppijanumero jonnekin

        self ! HenkiloDone(saved.tunniste)

      case HenkiloDone(tunniste) =>
        henkilot = henkilot.filterNot(_._1 == tunniste)
        if (henkilot.size == 0) {
          parent ! ProcessedBatch(b)
          context.stop(self)
        }
    }
  }
}


import org.scalatra.util.RicherString._

trait ImportTunniste {
  val tunniste: String
}
case class ImportHetu(hetu: String) extends ImportTunniste {
  override def toString: String = s"hetu: $hetu"
  override val tunniste = hetu
}
case class ImportOppijanumero(oppijanumero: String) extends ImportTunniste {
  override def toString: String = s"oppijanumero: $oppijanumero"
  override val tunniste = oppijanumero
}
case class ImportHenkilonTunniste(henkilonTunniste: String, syntymaAika: String, sukupuoli: String) extends ImportTunniste {
  override def toString: String = s"henkilonTunniste: $henkilonTunniste, syntymaAika: $syntymaAika, sukupuoli $sukupuoli"
  override val tunniste = henkilonTunniste
}

case class ImportHenkilo(tunniste: ImportTunniste, lahtokoulu: String, luokka: String, sukunimi: String, etunimet: String,
                         kutsumanimi: String, kotikunta: String, aidinkieli: String, kansalaisuus: Option[String],
                         lahiosoite: Option[String], postinumero: Option[String], maa: Option[String], matkapuhelin: Option[String],
                         muuPuhelin: Option[String], suoritukset: Seq[VirallinenSuoritus]) {
  def toHenkilo: Henkilo = Henkilo(
    oidHenkilo = None,
    hetu = tunniste match {
      case ImportHetu(h) => Some(h)
      case _ => None
    },
    henkiloTyyppi = "OPPIJA",
    etunimet = Some(etunimet),
    kutsumanimi = Some(kutsumanimi),
    sukunimi = Some(sukunimi),
    kotikunta = Some(kotikunta),
    aidinkieli = Some(Kieli(aidinkieli))
  )
  def toUpdatedHenkilo(h: Henkilo): Henkilo = h.copy(
    id = h.id,
    oidHenkilo = h.oidHenkilo,
    hetu = h.hetu,
    henkiloTyyppi = h.henkiloTyyppi,
    etunimet = Some(etunimet),
    kutsumanimi = Some(kutsumanimi),
    sukunimi = Some(sukunimi),
    kotikunta = Some(kotikunta),
    aidinkieli = Some(Kieli(aidinkieli))
  )
}

object ImportHenkilo {
  def getField(name: String)(h: Node): String = (h \ name).head.text
  def getOptionField(name: String)(h: Node): Option[String] = (h \ name).headOption.flatMap(_.text.blankOption)
  def suoritus(name: String, komoOid: String, oppijanumero: Option[String], yksilollistetty: Boolean)(h: Node): Option[VirallinenSuoritus] = (h \ name).headOption.map(s => {
    val valmistuminen = getField("valmistuminen")(s)
    val myontaja = getField("myontaja")(s)
    val suorituskieli = getField("suorituskieli")(s)
    val tila = getField("tila")(s)
    val yks = yksilollistetty match {
      case true => yksilollistaminen.withName(getField("yksilollistaminen")(s).toLowerCase.capitalize)
      case false => yksilollistaminen.Ei
    }
    VirallinenSuoritus(
      komo = komoOid,
      myontaja = myontaja,
      tila = tila,
      valmistuminen = new LocalDate(valmistuminen),
      henkilo = oppijanumero.getOrElse(""),
      yksilollistaminen = yks,
      suoritusKieli = suorituskieli,
      lahde = "tiedonsiirto"
    )
  })

  def apply(h: Node): ImportHenkilo = {
    val hetu = getOptionField("hetu")(h)
    val oppijanumero = getOptionField("oppijanumero")(h)
    val henkilonTunniste = getOptionField("henkilonTunniste")(h)
    val syntymaAika = getOptionField("syntymaAika")(h)
    val sukupuoli = getOptionField("sukupuoli")(h)

    val tunniste = (hetu, oppijanumero, henkilonTunniste, syntymaAika, sukupuoli) match {
      case (Some(henkilotunnus), _, _, _, _) => ImportHetu(henkilotunnus)
      case (_, Some(o), _, _, _) => ImportOppijanumero(o)
      case (_, _, Some(t), Some(sa), Some(sp)) => ImportHenkilonTunniste(t, sa, sp)
      case _ => throw new IllegalArgumentException(s"henkilöä ei voitu tunnistaa: hetu, oppijanumero tai henkilonTunniste+syntymaAika+sukupuoli puuttuu")
    }

    val suoritukset = Seq(
      suoritus("perusopetus", Config.perusopetusKomoOid, oppijanumero, yksilollistetty = true)(h),
      suoritus("perusopetuksenlisaopetus", Config.lisaopetusKomoOid, oppijanumero, yksilollistetty = true)(h),
      suoritus("ammattistartti", Config.ammattistarttiKomoOid, oppijanumero, yksilollistetty = false)(h),
      suoritus("valmentava", Config.valmentavaKomoOid, oppijanumero, yksilollistetty = false)(h),
      suoritus("maahanmuuttajienlukioonvalmistava", Config.lukioonvalmistavaKomoOid, oppijanumero, yksilollistetty = false)(h),
      suoritus("maahanmuuttajienammvalmistava", Config.ammatilliseenvalmistavaKomoOid, oppijanumero, yksilollistetty = false)(h),
      suoritus("ulkomainen", Config.ulkomainenkorvaavaKomoOid, oppijanumero, yksilollistetty = false)(h),
      suoritus("lukio", Config.lukioKomoOid, oppijanumero, yksilollistetty = false)(h),
      suoritus("ammatillinen", Config.ammatillinenKomoOid, oppijanumero, yksilollistetty = false)(h)
    ).flatten

    ImportHenkilo(
      tunniste = tunniste,
      lahtokoulu = getField("lahtokoulu")(h),
      luokka = getField("luokka")(h),
      sukunimi = getField("sukunimi")(h),
      etunimet = getField("etunimet")(h),
      kutsumanimi = getField("kutsumanimi")(h),
      kotikunta = getField("kotikunta")(h),
      aidinkieli = getField("aidinkieli")(h),
      kansalaisuus = getOptionField("kansalaisuus")(h),
      lahiosoite = getOptionField("lahiosoite")(h),
      postinumero = getOptionField("postinumero")(h),
      maa = getOptionField("maa")(h),
      matkapuhelin = getOptionField("matkapuhelin")(h),
      muuPuhelin = getOptionField("muuPuhelin")(h),
      suoritukset)
  }
}
