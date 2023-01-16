package fi.vm.sade.hakurekisteri.integration.haku

import fi.vm.sade.hakurekisteri.dates.Ajanjakso
import fi.vm.sade.hakurekisteri.integration.tarjonta.TarjontaRestHaku
import fi.vm.sade.hakurekisteri.tools.RicherString.stringToRicherString
import org.joda.time.{DateTime, ReadableInstant}

case class Haku(
  nimi: Kieliversiot,
  oid: String,
  aika: Ajanjakso,
  kausi: String,
  vuosi: Int,
  koulutuksenAlkamiskausi: Option[String],
  koulutuksenAlkamisvuosi: Option[Int],
  kkHaku: Boolean,
  toisenAsteenHaku: Boolean,
  viimeinenHakuaikaPaattyy: Option[DateTime],
  kohdejoukkoUri: Option[String],
  hakutapaUri: String,
  hakutyyppiUri: Option[String],
  hakulomakeAtaruId: Option[String] = None
) {
  val isActive: Boolean = aika.isCurrently

  def isJatkuvaHaku = hakutapaUri.split('#').head.equals("hakutapa_03")
}

object Haku {

  val toisenAsteenUrit = Set(
    "haunkohdejoukko_11",
    "haunkohdejoukko_20",
    "haunkohdejoukko_21",
    "haunkohdejoukko_22",
    "haunkohdejoukko_23",
    "haunkohdejoukko_24"
  )

  def apply(haku: RestHaku)(loppu: ReadableInstant): Haku = {
    val ajanjakso = Ajanjakso(findStart(haku), loppu)
    Haku(
      Kieliversiot(
        haku.nimi.get("kieli_fi").flatMap(Option(_)).flatMap(_.blankOption),
        haku.nimi.get("kieli_sv").flatMap(Option(_)).flatMap(_.blankOption),
        haku.nimi.get("kieli_en").flatMap(Option(_)).flatMap(_.blankOption)
      ),
      haku.oid.get,
      ajanjakso,
      haku.hakukausiUri,
      haku.hakukausiVuosi,
      haku.koulutuksenAlkamiskausiUri,
      haku.koulutuksenAlkamisVuosi,
      kkHaku = haku.kohdejoukkoUri.exists(_.startsWith("haunkohdejoukko_12")),
      toisenAsteenHaku = toisenAsteenUrit.contains(
        haku.kohdejoukkoUri.map(uri => uri.split("#").head).getOrElse("none")
      ),
      viimeinenHakuaikaPaattyy = findHakuajanPaatos(haku),
      kohdejoukkoUri = haku.kohdejoukkoUri,
      hakutapaUri = haku.hakutapaUri,
      hakutyyppiUri = None,
      hakulomakeAtaruId = haku.hakulomakeAtaruId
    )
  }

  def apply(haku: TarjontaRestHaku)(loppu: ReadableInstant): Haku = {
    val ajanjakso = Ajanjakso(findStart(haku.toRestHaku), loppu)
    Haku(
      Kieliversiot(
        haku.nimi.get("kieli_fi").flatMap(Option(_)).flatMap(_.blankOption),
        haku.nimi.get("kieli_sv").flatMap(Option(_)).flatMap(_.blankOption),
        haku.nimi.get("kieli_en").flatMap(Option(_)).flatMap(_.blankOption)
      ),
      haku.oid.get,
      ajanjakso,
      haku.hakukausiUri,
      haku.hakukausiVuosi,
      haku.koulutuksenAlkamiskausiUri,
      haku.koulutuksenAlkamisVuosi,
      kkHaku = haku.kohdejoukkoUri.exists(_.startsWith("haunkohdejoukko_12")),
      toisenAsteenHaku = haku.kohdejoukkoUri.exists(_.startsWith("haunkohdejoukko_11")),
      viimeinenHakuaikaPaattyy = findHakuajanPaatos(haku.toRestHaku),
      kohdejoukkoUri = haku.kohdejoukkoUri,
      hakutapaUri = haku.hakutapaUri,
      hakutyyppiUri = Some(haku.hakutyyppiUri),
      hakulomakeAtaruId = haku.ataruLomakeAvain
    )
  }

  def findHakuajanPaatos(haku: RestHaku): Option[DateTime] = {
    val sortedHakuajat = haku.hakuaikas.sortBy(_.alkuPvm)
    sortedHakuajat.lastOption.flatMap(_.loppuPvm.map(new DateTime(_)))
  }

  def findStart(haku: RestHaku): DateTime = {
    new DateTime(haku.hakuaikas.map(_.alkuPvm).sorted.head)
  }
}
