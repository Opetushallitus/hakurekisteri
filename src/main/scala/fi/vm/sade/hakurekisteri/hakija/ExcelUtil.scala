package fi.vm.sade.hakurekisteri.hakija

import info.folone.scala.poi._
import scalaz._
import syntax.monoid._
import syntax.foldable._
import std.list._
import java.io.OutputStream
import scalaz.effect.IO
import fi.vm.sade.hakurekisteri.hakija.XMLHakijat
import fi.vm.sade.hakurekisteri.hakija.XMLHakijat
import info.folone.scala.poi.StringCell

object ExcelUtil {

  val zero = BigDecimal.valueOf(0)

  def getHeaders(): Set[Row] = {
    Set(Row(0)(Set(
      StringCell(0, "Hetu"),
      StringCell(1, "Sukunimi"),
      StringCell(2, "Etunimet"),
      StringCell(3, "Kutsumanimi"),
      StringCell(4, "Lahiosoite"),
      StringCell(5, "Postinumero"),
      StringCell(6, "Postitmp"),
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
      StringCell(22, "Pohjakoulutus"),
      StringCell(23, "Todistusvuosi"),
      StringCell(24, "Julkaisulupa"),
      StringCell(25, "Yhteisetaineet"),
      StringCell(26, "Lukiontasapisteet"),
      StringCell(27, "Yleinenkoulumenestys"),
      StringCell(28, "Painotettavataineet"),
      StringCell(29, "Hakujno"),
      StringCell(30, "Oppilaitos"),
      StringCell(31, "Opetuspiste"),
      StringCell(32, "Opetuspisteennimi"),
      StringCell(33, "Koulutus"),
      StringCell(34, "Joustoperuste"),
      StringCell(35, "Yhteispisteet"),
      StringCell(36, "Valinta"),
      StringCell(37, "Vastaanotto"),
      StringCell(38, "Lasnaolo"),
      StringCell(39, "Terveys"),
      StringCell(40, "Aiempiperuminen")
    )))
  }

  def getRows(hakijat: XMLHakijat): Set[Row] = {
    hakijat.hakijat.flatMap((h) => h.hakemus.hakutoiveet.map(ht => Set[Cell](
      StringCell(0, h.hetu),
      StringCell(1, h.sukunimi),
      StringCell(2, h.etunimet),
      StringCell(3, h.kutsumanimi.getOrElse("")),
      StringCell(4, h.lahiosoite),
      StringCell(5, h.postinumero),
      StringCell(6, ""), // TODO resolve
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
      StringCell(22, h.hakemus.pohjakoulutus),
      StringCell(23, h.hakemus.todistusvuosi.getOrElse("")),
      StringCell(24, if (h.hakemus.julkaisulupa.getOrElse(false)) "X" else ""),
      StringCell(25, h.hakemus.yhteisetaineet.getOrElse(zero).toString),
      StringCell(26, h.hakemus.lukiontasapisteet.getOrElse(zero).toString),
      StringCell(27, h.hakemus.yleinenkoulumenestys.getOrElse(zero).toString),
      StringCell(28, h.hakemus.painotettavataineet.getOrElse(zero).toString),
      StringCell(29, ht.hakujno.toString),
      StringCell(30, ht.oppilaitos),
      StringCell(31, ht.opetuspiste.getOrElse("")),
      StringCell(32, ht.opetuspisteennimi.getOrElse("")),
      StringCell(33, ht.koulutus),
      StringCell(34, ""), // TODO miten suhtautuu Harkinnanvaraisuusperusteeseen?
      StringCell(35, ht.yhteispisteet.getOrElse(zero).toString),
      StringCell(36, ht.valinta.getOrElse("")),
      StringCell(37, ht.vastaanotto.getOrElse("")),
      StringCell(38, ht.lasnaolo.getOrElse("")),
      StringCell(39, if (ht.terveys.getOrElse(false)) "X" else ""),
      StringCell(40, if (ht.aiempiperuminen.getOrElse(false)) "X" else "")
    ))).zipWithIndex.map((t) => Row(t._2 + 1)(t._1)).toSet
  }

  def writeHakijatAsExcel(hakijat: XMLHakijat, out: OutputStream) {
    val sheet = new Sheet("Hakijat")(getHeaders ++ getRows(hakijat))

    val wb = new Workbook(Set(sheet))
    wb.safeToStream(out).map((t) => t.fold(th => throw th, identity)).unsafePerformIO()
  }

}
