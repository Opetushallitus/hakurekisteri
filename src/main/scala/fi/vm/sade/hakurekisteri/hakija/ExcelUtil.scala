package fi.vm.sade.hakurekisteri.hakija

import info.folone.scala.poi._
import java.io.{Writer, OutputStream}
import info.folone.scala.poi.StringCell
import org.slf4j.LoggerFactory

object ExcelUtil {
  val logger = LoggerFactory.getLogger(getClass)
  val zero = BigDecimal.valueOf(0)

  def getHeaders(): Set[Row] = {
    Set(Row(0)(Set(
      StringCell(0, "Hetu"),
      StringCell(1, "Oppijanumero"),
      StringCell(2, "Sukunimi"),
      StringCell(3, "Etunimet"),
      StringCell(4, "Kutsumanimi"),
      StringCell(5, "Lahiosoite"),
      StringCell(6, "Postinumero"),
      StringCell(7, "Maa"),
      StringCell(8, "Kansalaisuus"),
      StringCell(9, "Matkapuhelin"),
      StringCell(10, "Muupuhelin"),
      StringCell(11, "Sahkoposti"),
      StringCell(12, "Kotikunta"),
      StringCell(13, "Sukupuoli"),
      StringCell(14, "Aidinkieli"),
      StringCell(15, "Koulutusmarkkinointilupa"),
      StringCell(16, "Vuosi"),
      StringCell(17, "Kausi"),
      StringCell(18, "Hakemusnumero"),
      StringCell(19, "Lahtokoulu"),
      StringCell(20, "Lahtokoulunnimi"),
      StringCell(21, "Luokka"),
      StringCell(22, "Luokkataso"),
      StringCell(23, "Pohjakoulutus"),
      StringCell(24, "Todistusvuosi"),
      StringCell(25, "Julkaisulupa"),
      StringCell(26, "Yhteisetaineet"),
      StringCell(27, "Lukiontasapisteet"),
      StringCell(28, "Yleinenkoulumenestys"),
      StringCell(29, "Lisapistekoulutus"),
      StringCell(30, "Painotettavataineet"),
      StringCell(31, "Hakujno"),
      StringCell(32, "Oppilaitos"),
      StringCell(33, "Opetuspiste"),
      StringCell(34, "Opetuspisteennimi"),
      StringCell(35, "Koulutus"),
      StringCell(36, "Harkinnanvaraisuuden peruste"),
      StringCell(37, "Urheilijan ammatillinen koulutus"),
      StringCell(38, "Yhteispisteet"),
      StringCell(39, "Valinta"),
      StringCell(40, "Vastaanotto"),
      StringCell(41, "Lasnaolo"),
      StringCell(42, "Terveys"),
      StringCell(43, "Aiempiperuminen"),
      StringCell(44, "Kaksoistutkinto")
    )))
  }

  def getRows(hakijat: XMLHakijat): Set[Row] = {
    val rows: Set[Set[Cell]] = hakijat.hakijat.flatMap((h) => h.hakemus.hakutoiveet.map(ht => Set[Cell](
      StringCell(0, h.hetu),
      StringCell(1, h.oppijanumero),
      StringCell(2, h.sukunimi),
      StringCell(3, h.etunimet),
      StringCell(4, h.kutsumanimi.getOrElse("")),
      StringCell(5, h.lahiosoite),
      StringCell(6, h.postinumero),
      StringCell(7, h.maa),
      StringCell(8, h.kansalaisuus),
      StringCell(9, h.matkapuhelin.getOrElse("")),
      StringCell(10, h.muupuhelin.getOrElse("")),
      StringCell(11, h.sahkoposti.getOrElse("")),
      StringCell(12, h.kotikunta.getOrElse("")),
      StringCell(13, h.sukupuoli),
      StringCell(14, h.aidinkieli),
      StringCell(15, if (h.koulutusmarkkinointilupa) "X" else ""),
      StringCell(16, h.hakemus.vuosi),
      StringCell(17, h.hakemus.kausi),
      StringCell(18, h.hakemus.hakemusnumero),
      StringCell(19, h.hakemus.lahtokoulu.getOrElse("")),
      StringCell(20, h.hakemus.lahtokoulunnimi.getOrElse("")),
      StringCell(21, h.hakemus.luokka.getOrElse("")),
      StringCell(22, h.hakemus.luokkataso.getOrElse("")),
      StringCell(23, h.hakemus.pohjakoulutus),
      StringCell(24, h.hakemus.todistusvuosi.getOrElse("")),
      StringCell(25, if (h.hakemus.julkaisulupa.getOrElse(false)) "X" else ""),
      StringCell(26, h.hakemus.yhteisetaineet.getOrElse(zero).toString),
      StringCell(27, h.hakemus.lukiontasapisteet.getOrElse(zero).toString),
      StringCell(28, h.hakemus.yleinenkoulumenestys.getOrElse(zero).toString),
      StringCell(29, h.hakemus.lisapistekoulutus.getOrElse("").toString),
      StringCell(30, h.hakemus.painotettavataineet.getOrElse(zero).toString),
      StringCell(31, ht.hakujno.toString),
      StringCell(32, ht.oppilaitos),
      StringCell(33, ht.opetuspiste.getOrElse("")),
      StringCell(34, ht.opetuspisteennimi.getOrElse("")),
      StringCell(35, ht.koulutus),
      StringCell(36, ht.harkinnanvaraisuusperuste.getOrElse("")),
      StringCell(37, ht.urheilijanammatillinenkoulutus.getOrElse(false).toString),
      StringCell(38, ht.yhteispisteet.getOrElse(zero).toString),
      StringCell(39, ht.valinta.getOrElse("")),
      StringCell(40, ht.vastaanotto.getOrElse("")),
      StringCell(41, ht.lasnaolo.getOrElse("")),
      StringCell(42, if (ht.terveys.getOrElse(false)) "X" else ""),
      StringCell(43, if (ht.aiempiperuminen.getOrElse(false)) "X" else ""),
      StringCell(44, if (ht.kaksoistutkinto.getOrElse(false)) "X" else "")
    ))).toSet
    rows.zipWithIndex.map((rowWithIndex) => Row(rowWithIndex._2 + 1)(rowWithIndex._1))
  }

  def write(out: OutputStream, hakijat: XMLHakijat) {
    logger.debug("about to create xls")
    val sheet = new Sheet("Hakijat")(getHeaders ++ getRows(hakijat))
    val wb = new Workbook(Set(sheet))
    logger.debug("writing workbook: " + wb)

    wb.safeToStream(out).unsafePerformIO()
  }

}
