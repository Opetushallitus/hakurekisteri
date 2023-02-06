package fi.vm.sade.hakurekisteri.hakija.representation

import java.text.SimpleDateFormat
import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.hakija.representation.XMLHakemus.{
  getRelevantSuoritus,
  resolvePohjakoulutus,
  resolveYear
}
import fi.vm.sade.hakurekisteri.integration.organisaatio.Organisaatio
import fi.vm.sade.hakurekisteri.integration.valintatulos.Ilmoittautumistila._
import fi.vm.sade.hakurekisteri.integration.valintatulos.Valintatila.Valintatila
import fi.vm.sade.hakurekisteri.integration.valintatulos.{
  Ilmoittautumistila,
  Valintatila,
  Vastaanottotila
}
import fi.vm.sade.hakurekisteri.integration.valintatulos.Vastaanottotila.Vastaanottotila
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen.{Alueittain, Ei, Kokonaan, Osittain}
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, VirallinenSuoritus}

import scala.util.Try
import scala.util.matching.Regex
import scala.xml.Node

case class XMLHakija(
  hetu: String,
  oppijanumero: String,
  sukunimi: String,
  etunimet: String,
  kutsumanimi: Option[String],
  lahiosoite: String,
  postinumero: String,
  postitoimipaikka: String,
  maa: String,
  kansalaisuus: String,
  matkapuhelin: Option[String],
  muupuhelin: Option[String],
  sahkoposti: Option[String],
  kotikunta: Option[String],
  sukupuoli: String,
  aidinkieli: String,
  koulutusmarkkinointilupa: Boolean,
  hakemus: XMLHakemus
) {

  import XMLUtil._
  def toXml: Node = {
    <Hakija>
      <Hetu>{hetu}</Hetu>
      <Oppijanumero>{oppijanumero}</Oppijanumero>
      <Sukunimi>{sukunimi}</Sukunimi>
      <Etunimet>{etunimet}</Etunimet>
      {if (kutsumanimi.isDefined) <Kutsumanimi>{kutsumanimi.get}</Kutsumanimi>}
      <Lahiosoite>{lahiosoite}</Lahiosoite>
      <Postinumero>{postinumero}</Postinumero>
      <Postitoimipaikka>{postitoimipaikka}</Postitoimipaikka>
      <Maa>{maa}</Maa>
      <Kansalaisuus>{kansalaisuus}</Kansalaisuus>
      {if (matkapuhelin.isDefined) <Matkapuhelin>{matkapuhelin.get}</Matkapuhelin>}
      {if (muupuhelin.isDefined) <Muupuhelin>{muupuhelin.get}</Muupuhelin>}
      {if (sahkoposti.isDefined) <Sahkoposti>{sahkoposti.get}</Sahkoposti>}
      {if (kotikunta.isDefined) <Kotikunta>{kotikunta.get}</Kotikunta>}
      <Sukupuoli>{sukupuoli}</Sukupuoli>
      <Aidinkieli>{aidinkieli}</Aidinkieli>
      <Koulutusmarkkinointilupa>{toBooleanX(koulutusmarkkinointilupa)}</Koulutusmarkkinointilupa>
      {hakemus.toXml}
    </Hakija>
  }
}

object XMLHakija {
  import fi.vm.sade.hakurekisteri.tools.RicherString._

  private[hakija] def apply(hakija: Hakija, hakemus: XMLHakemus): XMLHakija =
    XMLHakija(
      hetu = hetu(hakija.henkilo.hetu, hakija.henkilo.syntymaaika),
      oppijanumero = hakija.henkilo.oppijanumero,
      sukunimi = hakija.henkilo.sukunimi,
      etunimet = hakija.henkilo.etunimet,
      kutsumanimi = hakija.henkilo.kutsumanimi.blankOption,
      lahiosoite = hakija.henkilo.lahiosoite,
      postinumero = hakija.henkilo.postinumero,
      postitoimipaikka = hakija.henkilo.postitoimipaikka,
      maa = hakija.henkilo.maa,
      kansalaisuus = hakija.henkilo.kansalaisuus.getOrElse(""),
      matkapuhelin = hakija.henkilo.matkapuhelin.blankOption,
      muupuhelin = hakija.henkilo.puhelin.blankOption,
      sahkoposti = hakija.henkilo.sahkoposti.blankOption,
      kotikunta = hakija.henkilo.kotikunta.blankOption,
      sukupuoli = Hakija.resolveSukupuoli(hakija),
      aidinkieli = hakija.henkilo.asiointiKieli,
      koulutusmarkkinointilupa = hakija.henkilo.markkinointilupa.getOrElse(false),
      hakemus = hakemus
    )

  def hetu(hetu: String, syntymaaika: String): String = hetu match {
    case "" =>
      Try(
        new SimpleDateFormat("ddMMyyyy").format(
          new SimpleDateFormat("dd.MM.yyyy").parse(syntymaaika)
        )
      ).getOrElse("")
    case _ => hetu
  }

}

case class XMLHakijat(hakijat: Seq[XMLHakija]) {
  def toXml: Node = {
    <Hakijat xmlns="http://service.henkilo.sade.vm.fi/types/perusopetus/hakijat"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://service.henkilo.sade.vm.fi/types/perusopetus/hakijat hakijat.xsd">
      {hakijat.map(_.toXml)}
    </Hakijat>
  }
}

object XMLHakemus {
  def resolvePohjakoulutus(suoritus: Option[VirallinenSuoritus]): String = suoritus match {
    case Some(s) =>
      s.komo match {
        case "ulkomainen" => "0"
        case "peruskoulu" =>
          s.yksilollistaminen match {
            case Ei         => "1"
            case Osittain   => "2"
            case Alueittain => "3"
            case Kokonaan   => "6"
          }
        case "lukio" => "9"
      }
    case None => "7"
  }

  def getRelevantSuoritus(suoritukset: Seq[Suoritus]): Option[VirallinenSuoritus] = {
    suoritukset
      .collect { case s: VirallinenSuoritus => (s, resolvePohjakoulutus(Some(s)).toInt) }
      .sortBy(_._2)
      .map(_._1)
      .headOption
  }

  def resolveYear(suoritus: VirallinenSuoritus): Option[String] = suoritus match {
    case VirallinenSuoritus("ulkomainen", _, _, _, _, _, _, _, _, _, _, _) => None
    case VirallinenSuoritus(_, _, _, date, _, _, _, _, _, _, _, _) =>
      if (!Suoritus.realValmistuminenNotKnownLocalDate.equals(date))
        Some(date.getYear.toString)
      else
        None
  }

  private[hakija] def apply(
    hakija: Hakija,
    opiskelutieto: Option[Opiskelija],
    lahtokoulu: Option[Organisaatio],
    toiveet: Seq[XMLHakutoive],
    osaaminen: Option[XMLOsaaminen]
  ): XMLHakemus =
    XMLHakemus(
      vuosi = hakija.hakemus.hakutoiveet.headOption
        .flatMap(_.hakukohde.koulutukset.headOption.flatMap(_.alkamisvuosi))
        .getOrElse(""),
      kausi = hakija.hakemus.hakutoiveet.headOption
        .flatMap(_.hakukohde.koulutukset.headOption.flatMap(_.alkamiskausi.map(_.toString)))
        .getOrElse(""),
      hakemusnumero = hakija.hakemus.hakemusnumero,
      lahtokoulu = lahtokoulu.flatMap(o => o.oppilaitosKoodi),
      lahtokoulunnimi = lahtokoulu.flatMap(o => o.nimi.get("fi")),
      luokka = opiskelutieto.map(_.luokka),
      luokkataso = opiskelutieto.map(_.luokkataso),
      pohjakoulutus = resolvePohjakoulutus(getRelevantSuoritus(hakija.suoritukset)),
      todistusvuosi = getRelevantSuoritus(hakija.suoritukset).flatMap(resolveYear),
      muukoulutus = hakija.henkilo.muukoulutus,
      julkaisulupa = Some(hakija.hakemus.julkaisulupa),
      yhteisetaineet = None,
      lukiontasapisteet = None,
      lisapistekoulutus = hakija.hakemus.lisapistekoulutus,
      yleinenkoulumenestys = None,
      painotettavataineet = None,
      hakutoiveet = toiveet,
      osaaminen = osaaminen
    )
}

object XMLHakutoive {
  private[hakija] def apply(ht: Hakutoive, o: Organisaatio, k: String): XMLHakutoive =
    XMLHakutoive(
      ht.hakukohde.oid,
      ht.jno.toShort,
      k,
      o.toimipistekoodi,
      o.nimi.get("fi").orElse(o.nimi.get("sv").orElse(o.nimi.get("en"))),
      ht.hakukohde.hakukohdekoodi,
      ht.harkinnanvaraisuusperuste,
      ht.urheilijanammatillinenkoulutus,
      ht.yhteispisteet,
      ht.valinta.map(_.toString).flatMap(valinta.lift),
      ht.vastaanotto.map(_.toString).flatMap(vastaanotto.lift),
      lasnaolo(ht),
      ht.terveys,
      ht.aiempiperuminen,
      ht.kaksoistutkinto,
      ht.koulutuksenKieli,
      keskiarvo = ht.keskiarvo,
      urheilijanLisakysymykset = ht.urheilijanLisakysymykset
    )

  def lasnaolo(ht: Hakutoive): Option[String] = {
    ht.vastaanotto match {
      case Some(Vastaanottotila.VASTAANOTTANUT) =>
        ht.ilmoittautumistila.map(_ match {
          case EI_TEHTY              => "1"
          case LASNA_KOKO_LUKUVUOSI  => "2"
          case POISSA_KOKO_LUKUVUOSI => "3"
          case EI_ILMOITTAUTUNUT     => "4"
          case LASNA_SYKSY           => "5"
          case POISSA_SYKSY          => "6"
          case LASNA                 => "7"
          case POISSA                => "8"
        })
      case _ => None
    }
  }
  def valinta: PartialFunction[String, String] = {
    case "HYVAKSYTTY"                     => "1"
    case "HARKINNANVARAISESTI_HYVAKSYTTY" => "1"
    case "VARASIJALTA_HYVAKSYTTY"         => "1"
    case "VARALLA"                        => "2"
    case "HYLATTY"                        => "3"
    case "PERUNUT"                        => "4"
    case "PERUUNTUNUT"                    => "4"
    case "PERUUTETTU"                     => "5"
  }

  def vastaanotto: PartialFunction[String, String] = {
    case "KESKEN"                        => "1"
    case "VASTAANOTTANUT"                => "3"
    case "EHDOLLISESTI_VASTAANOTTANUT"   => "3"
    case "PERUNUT"                       => "4"
    case "EI_VASTAANOTETTU_MAARA_AIKANA" => "5"
    case "PERUUTETTU"                    => "6"
  }
}
case class XMLOsaaminen(
  yleinen_kielitutkinto_fi: Option[String],
  valtionhallinnon_kielitutkinto_fi: Option[String],
  yleinen_kielitutkinto_sv: Option[String],
  valtionhallinnon_kielitutkinto_sv: Option[String],
  yleinen_kielitutkinto_en: Option[String],
  valtionhallinnon_kielitutkinto_en: Option[String],
  yleinen_kielitutkinto_se: Option[String],
  valtionhallinnon_kielitutkinto_se: Option[String]
) {}

case class XMLHakemus(
  vuosi: String,
  kausi: String,
  hakemusnumero: String,
  lahtokoulu: Option[String],
  lahtokoulunnimi: Option[String],
  luokka: Option[String],
  luokkataso: Option[String],
  pohjakoulutus: String,
  todistusvuosi: Option[String],
  muukoulutus: Option[String],
  julkaisulupa: Option[Boolean],
  yhteisetaineet: Option[BigDecimal],
  lukiontasapisteet: Option[BigDecimal],
  lisapistekoulutus: Option[String],
  yleinenkoulumenestys: Option[BigDecimal],
  painotettavataineet: Option[BigDecimal],
  hakutoiveet: Seq[XMLHakutoive],
  osaaminen: Option[XMLOsaaminen]
) {
  import XMLUtil._
  def toXml: Node = {
    <Hakemus>
      <Vuosi>{vuosi}</Vuosi>
      <Kausi>{kausi}</Kausi>
      <Hakemusnumero>{hakemusnumero}</Hakemusnumero>
      {if (lahtokoulu.isDefined) <Lahtokoulu>{lahtokoulu.get}</Lahtokoulu>}
      {if (lahtokoulunnimi.isDefined) <Lahtokoulunnimi>{lahtokoulunnimi.get}</Lahtokoulunnimi>}
      {if (luokka.isDefined) <Luokka>{luokka.get}</Luokka>}
      {if (luokkataso.isDefined) <Luokkataso>{luokkataso.get}</Luokkataso>}
      <Pohjakoulutus>{pohjakoulutus}</Pohjakoulutus>
      {if (todistusvuosi.isDefined) <Todistusvuosi>{todistusvuosi.get}</Todistusvuosi>}
      {if (muukoulutus.isDefined) <Muukoulutus>{muukoulutus.get}</Muukoulutus>}
      {if (julkaisulupa.isDefined) <Julkaisulupa>{toBooleanX(julkaisulupa.get)}</Julkaisulupa>}
      {if (yhteisetaineet.isDefined) <Yhteisetaineet>{yhteisetaineet.get}</Yhteisetaineet>}
      {
      if (lukiontasapisteet.isDefined) <Lukiontasapisteet>{
        lukiontasapisteet.get
      }</Lukiontasapisteet>
    }
      {
      if (lisapistekoulutus.isDefined) <Lisapistekoulutus>{
        lisapistekoulutus.get
      }</Lisapistekoulutus>
    }
      {
      if (yleinenkoulumenestys.isDefined) <Yleinenkoulumenestys>{
        yleinenkoulumenestys.get
      }</Yleinenkoulumenestys>
    }
      {
      if (painotettavataineet.isDefined) <Painotettavataineet>{
        painotettavataineet.get
      }</Painotettavataineet>
    }
      <Hakutoiveet>
        {hakutoiveet.map(_.toXml)}
      </Hakutoiveet>
    </Hakemus>
  }
}

//Lukion urheilijalinjoille hakevilta kysyttävät kysymykset
case class UrheilijanLisakysymykset(
  peruskoulu: Option[String],
  keskiarvo: Option[String],
  tamakausi: Option[String],
  viimekausi: Option[String],
  toissakausi: Option[String],
  sivulaji: Option[String],
  valmennusryhma_seurajoukkue: Option[String],
  valmennusryhma_piirijoukkue: Option[String],
  valmennusryhma_maajoukkue: Option[String],
  valmentaja_nimi: Option[String],
  valmentaja_email: Option[String],
  valmentaja_puh: Option[String],
  laji: Option[String],
  liitto: Option[String],
  seura: Option[String]
)
object HakijaV6Hakemus {
  private[hakija] def apply(
    hakija: Hakija,
    opiskelutieto: Option[Opiskelija],
    lahtokoulu: Option[Organisaatio],
    toiveet: Seq[XMLHakutoive],
    osaaminen: Option[XMLOsaaminen]
  ): HakijaV6Hakemus =
    HakijaV6Hakemus(
      vuosi = hakija.hakemus.hakutoiveet.headOption
        .flatMap(_.hakukohde.koulutukset.headOption.flatMap(_.alkamisvuosi))
        .getOrElse(""),
      kausi = hakija.hakemus.hakutoiveet.headOption
        .flatMap(_.hakukohde.koulutukset.headOption.flatMap(_.alkamiskausi.map(_.toString)))
        .getOrElse(""),
      hakemusnumero = hakija.hakemus.hakemusnumero,
      hakemuksenJattopaiva =
        hakija.ataruHakemus.map(h => h.hakemusFirstSubmittedTime).getOrElse("ei tiedossa"),
      hakemuksenMuokkauspaiva =
        hakija.ataruHakemus.map(h => h.createdTime).getOrElse("ei tiedossa"),
      lahtokoulu = lahtokoulu.flatMap(o => o.oppilaitosKoodi),
      lahtokoulunnimi = lahtokoulu.flatMap(o => o.nimi.get("fi")),
      luokka = opiskelutieto.map(_.luokka),
      luokkataso = opiskelutieto.map(_.luokkataso),
      pohjakoulutus = resolvePohjakoulutus(getRelevantSuoritus(hakija.suoritukset)),
      todistusvuosi = getRelevantSuoritus(hakija.suoritukset).flatMap(resolveYear),
      muukoulutus = hakija.henkilo.muukoulutus,
      julkaisulupa = Some(hakija.hakemus.julkaisulupa),
      yhteisetaineet = None,
      lukiontasapisteet = None,
      lisapistekoulutus = hakija.hakemus.lisapistekoulutus,
      yleinenkoulumenestys = None,
      painotettavataineet = None,
      hakutoiveet = toiveet,
      osaaminen = osaaminen
    )
}

case class HakijaV6Hakemus(
  vuosi: String,
  kausi: String,
  hakemusnumero: String,
  hakemuksenJattopaiva: String, //alkuperäinen jättöpäivä
  hakemuksenMuokkauspaiva: String, //viimeisimmän version tallennuspäivä
  lahtokoulu: Option[String],
  lahtokoulunnimi: Option[String],
  luokka: Option[String],
  luokkataso: Option[String],
  pohjakoulutus: String,
  todistusvuosi: Option[String],
  muukoulutus: Option[String],
  julkaisulupa: Option[Boolean],
  yhteisetaineet: Option[BigDecimal],
  lukiontasapisteet: Option[BigDecimal],
  lisapistekoulutus: Option[String],
  yleinenkoulumenestys: Option[BigDecimal],
  painotettavataineet: Option[BigDecimal],
  hakutoiveet: Seq[XMLHakutoive],
  osaaminen: Option[XMLOsaaminen]
)
case class XMLHakutoive(
  hakukohdeOid: String,
  hakujno: Short,
  oppilaitos: String,
  opetuspiste: Option[String],
  opetuspisteennimi: Option[String],
  koulutus: String,
  harkinnanvaraisuusperuste: Option[String],
  urheilijanammatillinenkoulutus: Option[Boolean],
  yhteispisteet: Option[BigDecimal],
  valinta: Option[String],
  vastaanotto: Option[String],
  lasnaolo: Option[String],
  terveys: Option[Boolean],
  aiempiperuminen: Option[Boolean],
  kaksoistutkinto: Option[Boolean],
  koulutuksenKieli: Option[String],
  keskiarvo: Option[String] = None, //Valintalaskennan keskiarvo, HakijatV6 ->
  urheilijanLisakysymykset: Option[UrheilijanLisakysymykset] = None
) {

  import fi.vm.sade.hakurekisteri.hakija.representation.XMLUtil._
  def toXml: Node = {
    <Hakutoive>
      <Hakujno>{hakujno}</Hakujno>
      <Oppilaitos>{oppilaitos}</Oppilaitos>
      {if (opetuspiste.isDefined) <Opetuspiste>{opetuspiste.get}</Opetuspiste>}
      {
      if (opetuspisteennimi.isDefined) <Opetuspisteennimi>{
        opetuspisteennimi.get
      }</Opetuspisteennimi>
    }
      <Koulutus>{koulutus}</Koulutus>
      {
      if (harkinnanvaraisuusperuste.isDefined) <Harkinnanvaraisuusperuste>{
        harkinnanvaraisuusperuste.get
      }</Harkinnanvaraisuusperuste>
    }
      {
      if (urheilijanammatillinenkoulutus.isDefined) <Urheilijanammatillinenkoulutus>{
        toBoolean10(urheilijanammatillinenkoulutus.get)
      }</Urheilijanammatillinenkoulutus>
    }
      {if (yhteispisteet.isDefined) <Yhteispisteet>{yhteispisteet.get}</Yhteispisteet>}
      {if (valinta.isDefined) <Valinta>{valinta.get}</Valinta>}
      {if (vastaanotto.isDefined) <Vastaanotto>{vastaanotto.get}</Vastaanotto>}
      {if (lasnaolo.isDefined) <Lasnaolo>{lasnaolo.get}</Lasnaolo>}
      {if (terveys.isDefined) <Terveys>{toBooleanX(terveys.get)}</Terveys>}
      {
      if (aiempiperuminen.isDefined) <Aiempiperuminen>{
        toBooleanX(aiempiperuminen.get)
      }</Aiempiperuminen>
    }
      {
      if (kaksoistutkinto.isDefined) <Kaksoistutkinto>{
        toBooleanX(kaksoistutkinto.get)
      }</Kaksoistutkinto>
    }
      {if (koulutuksenKieli.isDefined) <KoulutuksenKieli>{koulutuksenKieli.get}</KoulutuksenKieli>}
    </Hakutoive>
  }
}

object XMLUtil {
  def toBooleanX(b: Boolean): String = if (b) "X" else ""
  def toBoolean10(b: Boolean): String = if (b) "1" else "0"
}
