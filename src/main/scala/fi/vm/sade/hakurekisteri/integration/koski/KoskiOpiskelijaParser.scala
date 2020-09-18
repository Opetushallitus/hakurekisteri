package fi.vm.sade.hakurekisteri.integration.koski

import fi.vm.sade.hakurekisteri.Oids
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import org.slf4j.LoggerFactory

class KoskiOpiskelijaParser {

  private val logger = LoggerFactory.getLogger(getClass)

  def createOpiskelija(henkiloOid: String, suoritusLuokka: SuoritusLuokka): Option[Opiskelija] = {
    val alku = suoritusLuokka.lasnaDate.toDateTimeAtStartOfDay
    var loppu = suoritusLuokka.suoritus.valmistuminen.toDateTimeAtStartOfDay
    val oppilaitosAndLuokka: Option[OppilaitosAndLuokka] = detectOppilaitosAndLuokka(suoritusLuokka)

    if (!loppu.isAfter(alku)) {
      logger.debug(s"!loppu.isAfter(alku) = $loppu isAfter $alku = false, henkiloOid=$henkiloOid")
      loppu = KoskiUtil.deadlineDate.toDateTimeAtStartOfDay
      if (!loppu.isAfter(alku)) {
        throw new RuntimeException(
          s"Valmistuminen ei voi olla ennen läsnäolon alkamispäivää henkilöOid: $henkiloOid, suoritusLuokka: $suoritusLuokka"
        )
      }
    }

    if (oppilaitosAndLuokka.isEmpty) {
      logger.error(
        s"Opiskelijan muodostus henkilölle ${henkiloOid} suoritusluokasta ${suoritusLuokka} epäonnistui."
      )
      None
    } else if (Oids.ammatillisetKomoOids contains suoritusLuokka.suoritus.komo) {
      None
    } else {
      //luokkatieto käytännössä
      val opiskelija = Opiskelija(
        oppilaitosOid = oppilaitosAndLuokka.get.oppilaitosOid,
        luokkataso = oppilaitosAndLuokka.get.luokkataso,
        luokka = oppilaitosAndLuokka.get.luokka,
        henkiloOid = henkiloOid,
        alkuPaiva = alku,
        loppuPaiva = Some(loppu),
        source = KoskiUtil.koski_integration_source
      )
      Some(opiskelija)
    }
  }

  private def detectOppilaitosAndLuokka(suoritus: SuoritusLuokka): Option[OppilaitosAndLuokka] = {
    val oppilaitoksesAndLuokkas: Map[String, OppilaitosAndLuokka] = Map(
      Oids.lukioKomoOid -> OppilaitosAndLuokka("L", suoritus.suoritus.myontaja, suoritus.luokka),
      Oids.lukioonvalmistavaKomoOid -> OppilaitosAndLuokka(
        "ML",
        suoritus.suoritus.myontaja,
        suoritus.luokka
      ),
      Oids.ammatillinenKomoOid -> OppilaitosAndLuokka(
        "AK",
        suoritus.suoritus.myontaja,
        suoritus.luokka
      ),
      Oids.ammatilliseenvalmistavaKomoOid -> OppilaitosAndLuokka(
        "M",
        suoritus.suoritus.myontaja,
        suoritus.luokka
      ),
      Oids.ammattistarttiKomoOid -> OppilaitosAndLuokka(
        "A",
        suoritus.suoritus.myontaja,
        suoritus.luokka
      ),
      Oids.valmentavaKomoOid -> OppilaitosAndLuokka(
        "V",
        suoritus.suoritus.myontaja,
        suoritus.luokka
      ),
      Oids.valmaKomoOid -> OppilaitosAndLuokka(
        "VALMA",
        suoritus.suoritus.myontaja,
        suoritus.luokka
      ),
      Oids.telmaKomoOid -> OppilaitosAndLuokka(
        "TELMA",
        suoritus.suoritus.myontaja,
        suoritus.luokka
      ),
      Oids.ammatillinentutkintoKomoOid -> OppilaitosAndLuokka(
        "",
        suoritus.suoritus.myontaja,
        suoritus.luokka
      ),
      Oids.perusopetuksenOppiaineenOppimaaraOid -> OppilaitosAndLuokka(
        "OPPIAINE",
        suoritus.suoritus.myontaja,
        "OPPIAINE"
      ),
      Oids.erikoisammattitutkintoKomoOid -> OppilaitosAndLuokka(
        "",
        suoritus.suoritus.myontaja,
        suoritus.luokka
      )
    )

    if (
      suoritus.suoritus.komo == Oids.perusopetusKomoOid
      && (suoritus.luokkataso
        .getOrElse("")
        .equals("9") || suoritus.luokkataso.getOrElse("").equals("AIK"))
    ) {
      Some(OppilaitosAndLuokka("9", suoritus.suoritus.myontaja, suoritus.luokka))
    } else if (suoritus.suoritus.komo == Oids.lisaopetusKomoOid) {
      if (suoritus.luokka.isEmpty) {
        Some(OppilaitosAndLuokka("10", suoritus.suoritus.myontaja, "10"))
      } else {
        Some(OppilaitosAndLuokka("10", suoritus.suoritus.myontaja, suoritus.luokka))
      }
    } else {
      oppilaitoksesAndLuokkas.get(suoritus.suoritus.komo).orElse(None)
    }
  }
}

case class OppilaitosAndLuokka(luokkataso: String, oppilaitosOid: String, luokka: String)
