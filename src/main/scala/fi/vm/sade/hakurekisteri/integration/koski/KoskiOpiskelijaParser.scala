package fi.vm.sade.hakurekisteri.integration.koski

import fi.vm.sade.hakurekisteri.Oids
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

class KoskiOpiskelijaParser {

  private val logger = LoggerFactory.getLogger(getClass)

  def createOpiskelija(henkiloOid: String, suoritusLuokka: SuoritusLuokka): Opiskelija = {

    logger.debug(s"suoritusLuokka=$suoritusLuokka, henkiloOid=$henkiloOid")
    var alku = suoritusLuokka.lasnaDate.toDateTimeAtStartOfDay
    var loppu = suoritusLuokka.suoritus.valmistuminen.toDateTimeAtStartOfDay
    var (luokkataso, oppilaitosOid, luokka) = detectOppilaitos(suoritusLuokka)

    if (!loppu.isAfter(alku)) {
      logger.debug(s"!loppu.isAfter(alku) = $loppu isAfter $alku = false, henkiloOid=$henkiloOid")
      loppu = KoskiUtil.deadlineDate.toDateTimeAtStartOfDay
      if (!loppu.isAfter(alku)) {
        //throw new RuntimeException(s"Valmistuminen ei voi olla ennen läsnäolon alkamispäivää henkilöOid: $henkiloOid, suoritusLuokk: $suoritusLuokka")
        alku = new DateTime(0L) //Sanity
      }
    }


    //throw new RuntimeException(s"Valmistuminen ei voi olla ennen läsnäolon alkamispäivää henkilöOid: $henkiloOid, suoritusLuokk: $suoritusLuokka")
    logger.debug(s"alku=$alku, henkiloOid=$henkiloOid")

    //luokkatieto käytännössä
    val op = Opiskelija(
      oppilaitosOid = oppilaitosOid,
      luokkataso = luokkataso,
      luokka = luokka,
      henkiloOid = henkiloOid,
      alkuPaiva = alku,
      loppuPaiva = Some(loppu),
      source = KoskiUtil.koski_integration_source
    )
    logger.debug("createOpiskelija={}", op)
    op
  }

  def getOppilaitosAndLuokka(luokkataso: String, luokkaSuoritus: SuoritusLuokka, komoOid: String): (String, String, String) = {
    komoOid match {
      // hae luokka 9C tai vast
      case Oids.perusopetusKomoOid => {
        (luokkataso, luokkaSuoritus.suoritus.myontaja, luokkaSuoritus.luokka)
      }
      case Oids.lisaopetusKomoOid => {
        var luokka = luokkaSuoritus.luokka
        if(luokka.isEmpty){
          luokka = "10"
        }
        (luokkataso, luokkaSuoritus.suoritus.myontaja, luokka)
      }
      case _ => (luokkataso, luokkaSuoritus.suoritus.myontaja, luokkaSuoritus.luokka)
    }
  }

  //noinspection ScalaStyle
  def detectOppilaitos(suoritus: SuoritusLuokka): (String, String, String) = suoritus match {
    case s if s.suoritus.komo == Oids.lukioKomoOid => getOppilaitosAndLuokka("L", s, Oids.lukioKomoOid)
    case s if s.suoritus.komo == Oids.lukioonvalmistavaKomoOid => getOppilaitosAndLuokka("ML", s, Oids.lukioonvalmistavaKomoOid)
    case s if s.suoritus.komo == Oids.ammatillinenKomoOid => getOppilaitosAndLuokka("AK", s, Oids.ammatillinenKomoOid)
    case s if s.suoritus.komo == Oids.ammatilliseenvalmistavaKomoOid => getOppilaitosAndLuokka("M", s, Oids.ammatilliseenvalmistavaKomoOid)
    case s if s.suoritus.komo == Oids.ammattistarttiKomoOid => getOppilaitosAndLuokka("A", s, Oids.ammattistarttiKomoOid)
    case s if s.suoritus.komo == Oids.valmentavaKomoOid => getOppilaitosAndLuokka("V", s, Oids.valmentavaKomoOid)
    case s if s.suoritus.komo == Oids.valmaKomoOid => getOppilaitosAndLuokka("VALMA", s, Oids.valmaKomoOid)
    case s if s.suoritus.komo == Oids.telmaKomoOid => getOppilaitosAndLuokka("TELMA", s, Oids.telmaKomoOid)
    case s if s.suoritus.komo == Oids.lisaopetusKomoOid => getOppilaitosAndLuokka("10", s, Oids.lisaopetusKomoOid)
    case s if s.suoritus.komo == Oids.perusopetusKomoOid && (s.luokkataso.getOrElse("").equals("9") || s.luokkataso.getOrElse("").equals("AIK")) => getOppilaitosAndLuokka("9", s, Oids.perusopetusKomoOid)
    // TODO: Luokkataso?
    case s if s.suoritus.komo == Oids.ammatillinentutkintoKomoOid => getOppilaitosAndLuokka("", s, Oids.ammatillinentutkintoKomoOid)
    case _ => ("", "", "")
  }
}
