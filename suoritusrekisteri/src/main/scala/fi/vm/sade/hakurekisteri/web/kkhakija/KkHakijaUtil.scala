package fi.vm.sade.hakurekisteri.web.kkhakija

import akka.util.Timeout
import fi.vm.sade.hakurekisteri.hakija.{Kevat, Lasna, Lasnaolo, Poissa, Puuttuu, Syksy}
import fi.vm.sade.hakurekisteri.integration.hakemus.Koulutustausta
import fi.vm.sade.hakurekisteri.integration.henkilo.Kieli
import fi.vm.sade.hakurekisteri.integration.koodisto.{
  GetKoodi,
  GetRinnasteinenKoodiArvoQuery,
  Koodi,
  KoodistoActorRef
}
import fi.vm.sade.hakurekisteri.integration.tarjonta.{Hakukohteenkoulutus, TarjontaKoodi}
import fi.vm.sade.hakurekisteri.integration.valintatulos.{Ilmoittautumistila, SijoitteluTulos}
import fi.vm.sade.hakurekisteri.suoritus.VirallinenSuoritus
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import java.text.{ParseException, SimpleDateFormat}
import java.util.{Calendar, Date}
import scala.concurrent.{ExecutionContext, Future}

object KkHakijaUtil {
  def getHakukohdeOids(hakutoiveet: Map[String, String]): Seq[String] = {
    hakutoiveet.filter((t) => t._1.endsWith("Koulutus-id") && t._2 != "").values.toSeq
  }

  def toKkSyntymaaika(d: Date): String = {
    val c = Calendar.getInstance()
    c.setTime(d)
    new SimpleDateFormat("ddMMyy").format(d) + (c.get(Calendar.YEAR) match {
      case y if y >= 2000             => "A"
      case y if y >= 1900 && y < 2000 => "-"
      case _                          => ""
    })
  }

  def getHetu(hetu: Option[String], syntymaaika: Option[String], hakemusnumero: String): String =
    hetu match {
      case Some(h) => h

      case None =>
        syntymaaika match {
          case Some(s) =>
            try {
              toKkSyntymaaika(new SimpleDateFormat("dd.MM.yyyy").parse(s))
            } catch {
              case t: ParseException =>
                throw InvalidSyntymaaikaException(
                  s"could not parse syntymäaika $s in hakemus $hakemusnumero"
                )
            }

          case None =>
            throw InvalidSyntymaaikaException(
              s"syntymäaika and hetu missing from hakemus $hakemusnumero"
            )

        }
    }

  def getAidinkieli(aidinkieli: Option[Kieli]): String = {
    aidinkieli match {
      case Some(aidinkieli) => aidinkieli.kieliKoodi
      case None             => "99"
    }
  }

  def getAsiointikieli(kielikoodi: String): String = kielikoodi match {
    case "suomi"    => "1"
    case "ruotsi"   => "2"
    case "englanti" => "3"
    case _          => "9"
  }

  def getPostitoimipaikka(koodi: Option[Koodi]): String = koodi match {
    case None => ""

    case Some(k) =>
      k.metadata.find(_.kieli == "FI") match {
        case None => ""

        case Some(m) => m.nimi
      }
  }

  def isYlioppilas(suoritukset: Seq[VirallinenSuoritus]): Boolean =
    suoritukset.exists(s => s.tila == "VALMIS" && s.vahvistettu)

  def getYoSuoritusVuosi(suoritukset: Seq[VirallinenSuoritus]): Option[String] = {
    suoritukset.find(p => p.tila == "VALMIS" && p.vahvistettu) match {
      case Some(m) => Some(m.valmistuminen.getYear().toString())
      case None    => None
    }
  }

  private val logger = LoggerFactory.getLogger(getClass)

  def getMaakoodi(koodiArvo: String, koodisto: KoodistoActorRef)(implicit
    timeout: Timeout,
    ec: ExecutionContext
  ): Future[String] = koodiArvo.toLowerCase match {
    case "fin" => Future.successful("246")
    case "rom" => Future.successful("642") //Romanian vanha maakoodiarvo
    case ""    => Future.successful("999")

    case arvo =>
      val maaFuture =
        (koodisto.actor ? GetRinnasteinenKoodiArvoQuery("maatjavaltiot1", arvo, "maatjavaltiot2"))
          .mapTo[String]
      maaFuture.failed.foreach { case e =>
        logger.error(s"failed to fetch country $koodiArvo")
      }
      maaFuture
  }

  def getToimipaikka(
    maa: String,
    postinumero: Option[String],
    kaupunkiUlkomaa: Option[String],
    koodisto: KoodistoActorRef
  )(implicit timeout: Timeout, ec: ExecutionContext): Future[String] = {
    if (maa == "246") {
      (koodisto.actor ? GetKoodi("posti", s"posti_${postinumero.getOrElse("00000")}"))
        .mapTo[Option[Koodi]]
        .map(getPostitoimipaikka)
    } else if (kaupunkiUlkomaa.isDefined) {
      Future.successful(kaupunkiUlkomaa.get)
    } else {
      Future.successful("")
    }
  }

  def getKausi(kausiKoodi: String, hakemusOid: String, koodisto: KoodistoActorRef)(implicit
    timeout: Timeout,
    ec: ExecutionContext
  ): Future[String] =
    kausiKoodi.split('#').headOption match {
      case None =>
        logger.warn(s"No hakukausi with koodi $kausiKoodi for hakemus $hakemusOid")
        Future.successful("")

      case Some(k) =>
        (koodisto.actor ? GetKoodi("kausi", k)).mapTo[Option[Koodi]].map {
          case None =>
            throw new InvalidKausiException(
              s"kausi not found with koodi $kausiKoodi on hakemus $hakemusOid"
            )

          case Some(kausi) => kausi.koodiArvo

        }
    }

  def getPohjakoulutukset(k: Koulutustausta): Seq[String] = {
    Map(
      "yo" -> k.pohjakoulutus_yo,
      "am" -> k.pohjakoulutus_am,
      "amt" -> k.pohjakoulutus_amt,
      "kk" -> k.pohjakoulutus_kk,
      "ulk" -> k.pohjakoulutus_ulk,
      "avoin" -> k.pohjakoulutus_avoin,
      "muu" -> k.pohjakoulutus_muu
    ).collect { case (key, Some("true")) => key }.toSeq
  }

  // TODO muuta kun valinta-tulos-service saa ilmoittautumiset sekvenssiksi

  /**
    * Return a Future of Sequence of Lasnaolos based on conversions from season/year information from Hakukohteenkoulutus
    *
    * Uses first of the Hakukohteenkoulutus that has valid data when determining start season/year of the Lasnaolo.
    * Hakukohteenkoulutus might have the information in a Long date or separately as season and year.
    * Prefer season and year, if they aren't present, try parsing first of the possible Long dates.
    */
  def getLasnaolot(
    t: SijoitteluTulos,
    hakukohde: String,
    hakemusOid: String,
    koulutukset: Seq[Hakukohteenkoulutus]
  )(implicit timeout: Timeout, ec: ExecutionContext): Future[Seq[Lasnaolo]] = {

    koulutukset
      .find(koulutusHasValidFieldsForParsing)
      .map(parseKausiVuosiPair)
      .map(
        kausiVuosiPairToLasnaoloSequenceFuture(_: (String, Int), t, hakemusOid, hakukohde)
      ) match {
      case Some(s) => s
      case None    => Future.successful(Seq.empty)
    }
  }

  /**
    * Use sijoittelunTulos and parsed kausiVuosiPair to determine Lasnaolos.
    */
  private def kausiVuosiPairToLasnaoloSequenceFuture(
    kvp: (String, Int),
    sijoittelunTulos: SijoitteluTulos,
    hakemusOid: String,
    hakukohde: String
  )(implicit timeout: Timeout, ec: ExecutionContext): Future[Seq[Lasnaolo]] = {

    val lukuvuosi: (Int, Int) = kausiVuosiToLukuvuosiPair(kvp)

    Future(
      sijoittelunTulos.ilmoittautumistila
        .get(hakemusOid, hakukohde)
        .map {
          case Ilmoittautumistila.EI_TEHTY =>
            Seq(Puuttuu(Syksy(lukuvuosi._1)), Puuttuu(Kevat(lukuvuosi._2)))
          case Ilmoittautumistila.LASNA_KOKO_LUKUVUOSI =>
            Seq(Lasna(Syksy(lukuvuosi._1)), Lasna(Kevat(lukuvuosi._2)))
          case Ilmoittautumistila.POISSA_KOKO_LUKUVUOSI =>
            Seq(Poissa(Syksy(lukuvuosi._1)), Poissa(Kevat(lukuvuosi._2)))
          case Ilmoittautumistila.EI_ILMOITTAUTUNUT =>
            Seq(Puuttuu(Syksy(lukuvuosi._1)), Puuttuu(Kevat(lukuvuosi._2)))
          case Ilmoittautumistila.LASNA_SYKSY =>
            Seq(Lasna(Syksy(lukuvuosi._1)), Poissa(Kevat(lukuvuosi._2)))
          case Ilmoittautumistila.POISSA_SYKSY =>
            Seq(Poissa(Syksy(lukuvuosi._1)), Lasna(Kevat(lukuvuosi._2)))
          case Ilmoittautumistila.LASNA  => Seq(Lasna(Kevat(lukuvuosi._2)))
          case Ilmoittautumistila.POISSA => Seq(Poissa(Kevat(lukuvuosi._2)))
          case _                         => Seq()
        }
        .getOrElse(Seq(Puuttuu(Syksy(lukuvuosi._1)), Puuttuu(Kevat(lukuvuosi._2))))
    )

  }

  /**
    * Returns a Pair(Season, Year) or throws an Exception
    *
    * Used for determining Lasnaolos.
    */
  private def parseKausiVuosiPair(k: Hakukohteenkoulutus): (String, Int) = {

    val kausi: Option[TarjontaKoodi] = k.koulutuksenAlkamiskausi
    val vuosi: Int = k.koulutuksenAlkamisvuosi.getOrElse(0)
    val pvms: Option[Set[Long]] = k.koulutuksenAlkamisPvms

    if (kausi.isDefined && kausi.get.arvo.nonEmpty && vuosi != 0) {
      (kausi.get.arvo.get, vuosi)
    } else {
      datetimeLongToKausiVuosiPair(
        pvms
          .getOrElse(Set())
          .collectFirst { case pvm: Long => pvm }
          .getOrElse(
            throw new scala.IllegalArgumentException(
              "Invalid Hakukohteenkoulutus data. Could not parse Lasnaolos."
            )
          )
      )
    }

  }

  /**
    * Returns true if Hakukohteenkoulutus has valid fields for parsing a start season/year
    */
  private def koulutusHasValidFieldsForParsing(k: Hakukohteenkoulutus): Boolean = {

    if (
      k.koulutuksenAlkamiskausi.isDefined &&
      k.koulutuksenAlkamiskausi.get.arvo.nonEmpty &&
      k.koulutuksenAlkamisvuosi.getOrElse(0) != 0 ||
      k.koulutuksenAlkamisPvms.isDefined &&
      k.koulutuksenAlkamisPvms.get.nonEmpty
    ) {
      true
    } else {
      false
    }

  }

  /**
    * Return Pair of years for autumn and spring seasons from passed in
    * Pair containing start season and year of the learning opportunity.
    */
  private def kausiVuosiToLukuvuosiPair(kv: (String, Int)): (Int, Int) = {
    kv._1 match {
      case "S" => (kv._2, kv._2 + 1)
      case "K" => (kv._2, kv._2)
      case _   => throw new scala.IllegalArgumentException("Invalid kausi " + kv._1)
    }
  }

  /**
    * Convert date in millis to a datetime and extract year/season
    * information from it as a Pair.
    */
  private def datetimeLongToKausiVuosiPair(d: Long): (String, Int) = {
    val date: DateTime = new DateTime(d)
    val year: Int = date.getYear
    val kausi: String = date.getMonthOfYear match {
      case m if m >= 1 && m <= 7  => "K" // 1.1 - 31.7    Spring season
      case m if m >= 8 && m <= 12 => "S" // 1.8 - 31.12   Autumn season
      case _                      => throw new scala.IllegalArgumentException("Invalid date provided.")
    }
    (kausi, year)
  }
}
