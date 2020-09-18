package fi.vm.sade.hakurekisteri.integration.haku

import fi.vm.sade.hakurekisteri.dates.Ajanjakso
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
  hakutyyppiUri: String
) {
  val isActive: Boolean = aika.isCurrently
}

object Haku {
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
      toisenAsteenHaku = haku.kohdejoukkoUri.exists(_.startsWith("haunkohdejoukko_11")),
      viimeinenHakuaikaPaattyy = findHakuajanPaatos(haku),
      kohdejoukkoUri = haku.kohdejoukkoUri,
      hakutapaUri = haku.hakutapaUri,
      hakutyyppiUri = haku.hakutyyppiUri
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
