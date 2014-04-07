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
    hakijat.hakijat.zipWithIndex.flatMap((t) =>
      t._1.hakemus.hakutoiveet.map(ht =>
        Row(t._2 + 1) {
          Set(
            StringCell(0, t._1.hetu),
            StringCell(1, t._1.sukunimi),
            StringCell(2, t._1.etunimet),
            StringCell(3, t._1.kutsumanimi.getOrElse("")),
            StringCell(4, t._1.lahiosoite),
            StringCell(5, t._1.postinumero),
            StringCell(6, ""),
            StringCell(7, t._1.maa),
            StringCell(8, t._1.kansalaisuus),
            StringCell(9, t._1.matkapuhelin.getOrElse("")),
            StringCell(10, t._1.muupuhelin.getOrElse("")),
            StringCell(11, t._1.sahkoposti.getOrElse("")),
            StringCell(12, t._1.kotikunta.getOrElse("")),
            StringCell(13, t._1.sukupuoli),
            StringCell(14, t._1.aidinkieli),
            StringCell(15, if (t._1.koulutusmarkkinointilupa) "X" else ""),
            StringCell(16, t._1.hakemus.vuosi),
            StringCell(17, t._1.hakemus.kausi),
            StringCell(18, t._1.hakemus.hakemusnumero),
            StringCell(19, t._1.hakemus.lahtokoulu.getOrElse("")),
            StringCell(20, t._1.hakemus.lahtokoulunnimi.getOrElse("")),
            StringCell(21, t._1.hakemus.luokka.getOrElse("")),
            StringCell(22, t._1.hakemus.pohjakoulutus),
            StringCell(23, t._1.hakemus.todistusvuosi.getOrElse("")),
            StringCell(24, if (t._1.hakemus.julkaisulupa.getOrElse(false)) "X" else ""),
            StringCell(25, t._1.hakemus.yhteisetaineet.getOrElse(BigDecimal.valueOf(0)).toString),
            StringCell(26, t._1.hakemus.lukiontasapisteet.getOrElse(BigDecimal.valueOf(0)).toString),
            StringCell(27, t._1.hakemus.yleinenkoulumenestys.getOrElse(BigDecimal.valueOf(0)).toString),
            StringCell(28, t._1.hakemus.painotettavataineet.getOrElse(BigDecimal.valueOf(0)).toString),
            StringCell(29, ht.hakujno.toString),
            StringCell(30, ht.oppilaitos),
            StringCell(31, ht.opetuspiste.getOrElse("")),
            StringCell(32, ht.opetuspisteennimi.getOrElse("")),
            StringCell(33, ht.koulutus),
            StringCell(34, ""),
            StringCell(35, ht.yhteispisteet.getOrElse(BigDecimal.valueOf(0)).toString),
            StringCell(36, ht.valinta.getOrElse("")),
            StringCell(37, ht.vastaanotto.getOrElse("")),
            StringCell(38, ht.lasnaolo.getOrElse("")),
            StringCell(39, if (ht.terveys.getOrElse(false)) "X" else ""),
            StringCell(40, if (ht.aiempiperuminen.getOrElse(false)) "X" else "")
          )
        }
      )
    ).toSet
  }

  def writeHakijatAsExcel(hakijat: XMLHakijat, out: OutputStream) {
    val sheet = new Sheet("Hakijat")(getHeaders ++ getRows(hakijat))

    val wb = new Workbook(Set(sheet))
    wb.safeToStream(out).map((t) => t.fold(th => throw th, identity)).unsafePerformIO()
  }

}
