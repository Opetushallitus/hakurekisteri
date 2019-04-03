package fi.vm.sade.hakurekisteri.integration.koski

import fi.vm.sade.hakurekisteri.Oids
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import org.slf4j.LoggerFactory

class KoskiOpiskelijaParser {

  private val logger = LoggerFactory.getLogger(getClass)

  def createOpiskelija(henkiloOid: String, suoritusLuokka: SuoritusLuokka): Opiskelija = {

    logger.debug(s"suoritusLuokka=$suoritusLuokka, henkiloOid=$henkiloOid")
    val alku = suoritusLuokka.lasnaDate.toDateTimeAtStartOfDay
    var loppu = suoritusLuokka.suoritus.valmistuminen.toDateTimeAtStartOfDay
    val oppilaitosAndLuokka: OppilaitosAndLuokka = detectOppilaitosAndLuokka(suoritusLuokka)

    if (!loppu.isAfter(alku)) {
      logger.debug(s"!loppu.isAfter(alku) = $loppu isAfter $alku = false, henkiloOid=$henkiloOid")
      loppu = KoskiUtil.deadlineDate.toDateTimeAtStartOfDay
      if (!loppu.isAfter(alku)) {
        throw new RuntimeException(s"Valmistuminen ei voi olla ennen läsnäolon alkamispäivää henkilöOid: $henkiloOid, suoritusLuokka: $suoritusLuokka")
      }
    }

    logger.debug(s"alku=$alku, henkiloOid=$henkiloOid")

    //luokkatieto käytännössä
    val op = Opiskelija(
      oppilaitosOid = oppilaitosAndLuokka.oppilaitosOid,
      luokkataso = oppilaitosAndLuokka.luokkataso,
      luokka = oppilaitosAndLuokka.luokka,
      henkiloOid = henkiloOid,
      alkuPaiva = alku,
      loppuPaiva = Some(loppu),
      source = KoskiUtil.koski_integration_source
    )
    logger.debug("createOpiskelija={}", op)
    op
  }

  private def detectOppilaitosAndLuokka(suoritus: SuoritusLuokka): OppilaitosAndLuokka = {
    val oppilaitoksesAndLuokkas: Map[String, OppilaitosAndLuokka] = Map(
      Oids.lukioKomoOid                   -> OppilaitosAndLuokka("L", suoritus.suoritus.myontaja, suoritus.luokka),
      Oids.lukioonvalmistavaKomoOid       -> OppilaitosAndLuokka("ML" , suoritus.suoritus.myontaja, suoritus.luokka),
      Oids.ammatillinenKomoOid            -> OppilaitosAndLuokka("AK" , suoritus.suoritus.myontaja, suoritus.luokka),
      Oids.ammatilliseenvalmistavaKomoOid -> OppilaitosAndLuokka("M" , suoritus.suoritus.myontaja, suoritus.luokka),
      Oids.ammattistarttiKomoOid          -> OppilaitosAndLuokka("A" , suoritus.suoritus.myontaja, suoritus.luokka),
      Oids.valmentavaKomoOid              -> OppilaitosAndLuokka("V" , suoritus.suoritus.myontaja, suoritus.luokka),
      Oids.valmaKomoOid                   -> OppilaitosAndLuokka("VALMA" , suoritus.suoritus.myontaja, suoritus.luokka),
      Oids.telmaKomoOid                   -> OppilaitosAndLuokka("TELMA" , suoritus.suoritus.myontaja, suoritus.luokka),
      Oids.ammatillinentutkintoKomoOid    -> OppilaitosAndLuokka("" , suoritus.suoritus.myontaja, suoritus.luokka)
    )

    if (suoritus.suoritus.komo == Oids.perusopetusKomoOid
      && (suoritus.luokkataso.getOrElse("").equals("9") || suoritus.luokkataso.getOrElse("").equals("AIK"))) {
      OppilaitosAndLuokka("9", suoritus.suoritus.myontaja, suoritus.luokka)
    } else if (suoritus.suoritus.komo == Oids.lisaopetusKomoOid) {
      if(suoritus.luokka.isEmpty){
        OppilaitosAndLuokka("10", suoritus.suoritus.myontaja, "10")
      } else {
        OppilaitosAndLuokka("10", suoritus.suoritus.myontaja, suoritus.luokka)
      }
    } else {
      oppilaitoksesAndLuokkas.getOrElse(suoritus.suoritus.komo, OppilaitosAndLuokka("", "", ""))
    }
  }
}

case class OppilaitosAndLuokka(luokkataso: String, oppilaitosOid: String, luokka: String)
