package fi.vm.sade.hakurekisteri.web.kkhakija

import fi.vm.sade.hakurekisteri.hakija.Hakuehto.Hakuehto
import fi.vm.sade.hakurekisteri.hakija.{Hakuehto, Lasnaolo}
import fi.vm.sade.hakurekisteri.integration.hakemus.{
  ApplicationAttachment,
  AtaruHakemus,
  FullHakemus,
  HakemusAnswers,
  HakemusAttachmentRequest,
  HakijaHakemus,
  PreferenceEligibility
}
import fi.vm.sade.hakurekisteri.integration.tarjonta.Hakukohteenkoulutus
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.{Lukuvuosimaksu, Maksuntila}
import fi.vm.sade.hakurekisteri.integration.valintatulos.{
  HyvaksymisenEhto,
  SijoitteluTulos,
  Valintatila
}
import fi.vm.sade.hakurekisteri.integration.valintatulos.Valintatila.Valintatila
import fi.vm.sade.hakurekisteri.integration.valintatulos.Vastaanottotila.Vastaanottotila
import fi.vm.sade.hakurekisteri.rest.support.User
import org.slf4j.LoggerFactory

import java.time.Instant
import java.util.Date

case class Hakemus(
  haku: String,
  hakuVuosi: Int,
  hakuKausi: String,
  hakemusnumero: String,
  hakemusViimeinenMuokkausAikaleima: Option[String],
  hakemusJattoAikaleima: Option[String],
  valinnanAikaleima: Option[String],
  organisaatio: String,
  hakukohde: String,
  hakutoivePrioriteetti: Option[Int],
  hakukohdeKkId: Option[String],
  avoinVayla: Option[Boolean],
  valinnanTila: Option[Valintatila],
  valintatapajononTyyppi: Option[String], //Tiedossa vain hyväksytyille hakijoille
  valintatapajononNimi: Option[String],
  hyvaksymisenEhto: Option[HyvaksymisenEhto],
  vastaanottotieto: Option[Vastaanottotila],
  pisteet: Option[BigDecimal],
  ilmoittautumiset: Seq[Lasnaolo],
  pohjakoulutus: Seq[String],
  julkaisulupa: Option[Boolean],
  hKelpoisuus: String,
  hKelpoisuusLahde: Option[String],
  hKelpoisuusMaksuvelvollisuus: Option[String],
  lukuvuosimaksu: Option[String],
  hakukohteenKoulutukset: Seq[KkHakukohteenkoulutus],
  liitteet: Option[Seq[Liite]]
)

case class Hakija(
  hetu: String,
  oppijanumero: String,
  sukunimi: String,
  etunimet: String,
  kutsumanimi: String,
  lahiosoite: String,
  postinumero: String,
  postitoimipaikka: String,
  maa: String,
  kansalaisuus: Option[String],
  kaksoiskansalaisuus: Option[String],
  kansalaisuudet: Option[List[String]],
  syntymaaika: Option[String],
  matkapuhelin: Option[String],
  puhelin: Option[String],
  sahkoposti: Option[String],
  kotikunta: String,
  sukupuoli: String,
  aidinkieli: String,
  asiointikieli: String,
  koulusivistyskieli: Option[String],
  koulusivistyskielet: Option[Seq[String]],
  koulutusmarkkinointilupa: Option[Boolean],
  onYlioppilas: Boolean,
  yoSuoritusVuosi: Option[String],
  turvakielto: Boolean,
  hakemukset: Seq[Hakemus],
  ensikertalainen: Option[Boolean]
)

case class HakijaV3(
  hetu: String,
  oppijanumero: String,
  sukunimi: String,
  etunimet: String,
  kutsumanimi: String,
  lahiosoite: String,
  postinumero: String,
  postitoimipaikka: String,
  maa: String,
  kansalaisuudet: Option[List[String]],
  syntymaaika: Option[String],
  matkapuhelin: Option[String],
  puhelin: Option[String],
  sahkoposti: Option[String],
  kotikunta: String,
  sukupuoli: String,
  aidinkieli: String,
  asiointikieli: String,
  koulusivistyskieli: Option[String],
  koulutusmarkkinointilupa: Option[Boolean],
  onYlioppilas: Boolean,
  yoSuoritusVuosi: Option[String],
  turvakielto: Boolean,
  hakemukset: Seq[Hakemus]
)

case class HakijaV4(
  hetu: String,
  oppijanumero: String,
  sukunimi: String,
  etunimet: String,
  kutsumanimi: String,
  lahiosoite: String,
  postinumero: String,
  postitoimipaikka: String,
  maa: String,
  kansalaisuudet: Option[List[String]],
  syntymaaika: Option[String],
  matkapuhelin: Option[String],
  puhelin: Option[String],
  sahkoposti: Option[String],
  kotikunta: String,
  sukupuoli: String,
  aidinkieli: String,
  asiointikieli: String,
  koulusivistyskieli: Option[String],
  koulutusmarkkinointilupa: Option[Boolean],
  onYlioppilas: Boolean,
  yoSuoritusVuosi: Option[String],
  turvakielto: Boolean,
  hakemukset: Seq[Hakemus]
)

case class HakijaV5(
  hetu: String,
  oppijanumero: String,
  sukunimi: String,
  etunimet: String,
  kutsumanimi: String,
  lahiosoite: String,
  postinumero: String,
  postitoimipaikka: String,
  maa: String,
  kansalaisuudet: Option[List[String]],
  syntymaaika: Option[String],
  matkapuhelin: Option[String],
  puhelin: Option[String],
  sahkoposti: Option[String],
  kotikunta: String,
  sukupuoli: String,
  aidinkieli: String,
  asiointikieli: String,
  koulusivistyskielet: Option[Seq[String]],
  koulutusmarkkinointilupa: Option[Boolean],
  onYlioppilas: Boolean,
  yoSuoritusVuosi: Option[String],
  turvakielto: Boolean,
  hakemukset: Seq[Hakemus],
  ensikertalainen: Option[Boolean]
)

case class InvalidSyntymaaikaException(m: String) extends Exception(m)
case class InvalidKausiException(m: String) extends Exception(m)

case class KkHakukohteenkoulutus(
  komoOid: String,
  tkKoulutuskoodi: String,
  kkKoulutusId: Option[String],
  koulutuksenAlkamiskausi: Option[String],
  koulutuksenAlkamisvuosi: Option[Int],
  johtaaTutkintoon: Option[Boolean]
) {
  def toExcelString: String =
    s"Koulutus(${komoOid},${tkKoulutuskoodi},${kkKoulutusId.getOrElse("")}," +
      s"${koulutuksenAlkamisvuosi.getOrElse("")},${koulutuksenAlkamiskausi.getOrElse("")}," +
      s"${johtaaTutkintoon.getOrElse("")})"
}

case class Liite(
  hakuId: String,
  hakuRyhmaId: String,
  tila: String,
  saapumisenTila: String,
  nimi: String,
  vastaanottaja: String
) {
  def toExcelString: String = s"Liite(${hakuId},${hakuRyhmaId},${tila},${saapumisenTila}," +
    s"${nimi},${vastaanottaja})"
}

object KkHakijaParamMissingException extends Exception

object KkHakijaHakemusUtil {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def getHakukelpoisuus(
    hakukohdeOid: String,
    kelpoisuudet: Seq[PreferenceEligibility]
  ): PreferenceEligibility = {
    kelpoisuudet.find(_.aoId == hakukohdeOid) match {
      case Some(h) => h

      case None =>
        val defaultState = ""
        PreferenceEligibility(hakukohdeOid, defaultState, None, None)

    }
  }

  def getKnownOrganizations(user: Option[User]): Set[String] =
    user.map(_.orgsFor("READ", "Hakukohde")).getOrElse(Set())

  import fi.vm.sade.hakurekisteri.integration.valintatulos.Valintatila.isHyvaksytty
  import fi.vm.sade.hakurekisteri.integration.valintatulos.Vastaanottotila.isVastaanottanut

  def matchHakuehto(
    valintaTulos: SijoitteluTulos,
    hakemusOid: String,
    hakukohdeOid: String
  ): (Hakuehto) => Boolean = {
    case Hakuehto.Kaikki          => true
    case Hakuehto.Hyvaksytyt      => matchHyvaksytyt(valintaTulos, hakemusOid, hakukohdeOid)
    case Hakuehto.Vastaanottaneet => matchVastaanottaneet(valintaTulos, hakemusOid, hakukohdeOid)
    case Hakuehto.Hylatyt         => matchHylatyt(valintaTulos, hakemusOid, hakukohdeOid)
  }

  def matchHylatyt(
    valintaTulos: SijoitteluTulos,
    hakemusOid: String,
    hakukohdeOid: String
  ): Boolean = {
    valintaTulos.valintatila.get(hakemusOid, hakukohdeOid).contains(Valintatila.HYLATTY)
  }

  def matchVastaanottaneet(
    valintaTulos: SijoitteluTulos,
    hakemusOid: String,
    hakukohdeOid: String
  ): Boolean = {
    valintaTulos.vastaanottotila.get(hakemusOid, hakukohdeOid).exists(isVastaanottanut)
  }

  def matchHyvaksytyt(
    valintaTulos: SijoitteluTulos,
    hakemusOid: String,
    hakukohdeOid: String
  ): Boolean = {
    valintaTulos.valintatila.get(hakemusOid, hakukohdeOid).exists(isHyvaksytty)
  }

  def filterTkKoulutuskoodi(koulutus: Hakukohteenkoulutus): String = {
    koulutus.johtaaTutkintoon match {
      case Some(koulutus.johtaaTutkintoon) => koulutus.tkKoulutuskoodi
      case _                               => ""
    }
  }

  def resolveLukuvuosiMaksu(
    hakemus: HakijaHakemus,
    hakukelpoisuus: PreferenceEligibility,
    lukuvuosimaksutByHakukohdeOid: Map[String, List[Lukuvuosimaksu]],
    hakukohdeOid: String
  ): Option[String] = {
    val hakukohteenMaksut = lukuvuosimaksutByHakukohdeOid.get(hakukohdeOid)
    if (hakukelpoisuus.maksuvelvollisuus.contains("REQUIRED") || hakukohteenMaksut.isDefined) {
      val maksuStatus = hakukohteenMaksut match {
        case None =>
          logger.info(
            s"Payment required for application yet no payment information found for application option "
              + s"$hakukohdeOid of application ${hakemus.oid}, defaulting to ${Maksuntila.maksamatta.toString}"
          )
          Lukuvuosimaksu(
            hakemus.personOid.get,
            hakukohdeOid,
            Maksuntila.maksamatta,
            "System",
            Date.from(Instant.now())
          )
        case Some(ainoaMaksu :: Nil) => ainoaMaksu
        case Some(montaMaksua) if montaMaksua.size > 1 =>
          logger.warn(
            s"Found several lukuvuosimaksus for application option $hakukohdeOid of application ${hakemus.oid}, " +
              s"picking the first one: $montaMaksua"
          )
          montaMaksua.head
      }
      Some(maksuStatus.maksuntila.toString)
    } else {
      None
    }
  }

  def attachmentToLiite(attachments: Seq[HakemusAttachmentRequest]): Option[Seq[Liite]] = {
    var liitteet: Seq[Liite] = attachments.map(a =>
      Liite(
        a.preferenceAoId.getOrElse(""),
        a.preferenceAoGroupId.getOrElse(""),
        a.receptionStatus,
        a.processingStatus,
        getLiitteenNimi(a.applicationAttachment),
        a.applicationAttachment.address.recipient
      )
    )
    liitteet match {
      case Seq() => None
      case _     => Some(liitteet)
    }
  }

  def getOsaaminenOsaalue(hakemusAnswers: Option[HakemusAnswers], key: String): String = {
    hakemusAnswers match {
      case Some(ha) =>
        ha.osaaminen match {
          case Some(a) => a.getOrElse(key, "")
          case None    => ""
        }
      case None => ""
    }
  }

  def getLiitteenNimi(liite: ApplicationAttachment): String = {
    (liite.name, liite.header) match {
      case (Some(a), _) => a.translations.fi
      case (_, Some(b)) => b.translations.fi
      case (None, None) => ""
    }
  }
  def getMaksuvelvollisuudet(hakemukset: Seq[HakijaHakemus]) = {
    val maksuvelvollisuudet: Set[String] = hakemukset
      .flatMap(_ match {
        case h: FullHakemus =>
          h.preferenceEligibilities.filter(_.maksuvelvollisuus.isDefined).map(_.aoId)
        case h: AtaruHakemus => h.paymentObligations.filter(_._2 == "REQUIRED").keys
        case _               => ???
      })
      .toSet
    maksuvelvollisuudet
  }
}
