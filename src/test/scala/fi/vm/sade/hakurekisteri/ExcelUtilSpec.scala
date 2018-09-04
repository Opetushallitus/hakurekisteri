package fi.vm.sade.hakurekisteri

import org.scalatra.test.scalatest.ScalatraFunSuite
import fi.vm.sade.hakurekisteri.hakija._
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import fi.vm.sade.hakurekisteri.hakija.representation.{XMLHakemus, XMLHakija, XMLHakijat, XMLHakutoive}
import org.apache.poi.ss.usermodel.{Workbook, WorkbookFactory}

import scala.collection.immutable.IndexedSeq

class ExcelUtilSpec extends ScalatraFunSuite {

  test("write as excel should return non zero length result") {
    val hakijat = XMLHakijat(Seq(XMLHakija(
      hetu = "",
      oppijanumero = "1.1",
      sukunimi = "A",
      etunimet = "B",
      kutsumanimi = None,
      lahiosoite = "K",
      postinumero = "00000",
      postitoimipaikka = "NotHelsinki",
      maa = "246",
      kansalaisuus = List("246"),
      matkapuhelin = None,
      muupuhelin = None,
      sahkoposti = None,
      kotikunta = None,
      sukupuoli = "1",
      aidinkieli = "FI",
      koulutusmarkkinointilupa = false,
      XMLHakemus(
        vuosi = "2014",
        kausi = "S",
        hakemusnumero = "1.2",
        lahtokoulu = None,
        lahtokoulunnimi = None,
        luokka = None,
        luokkataso = None,
        pohjakoulutus = "1",
        todistusvuosi = Some("2014"),
        muukoulutus = Some("muukoulutushere 1"),
        julkaisulupa = None,
        yhteisetaineet = None,
        lukiontasapisteet = None,
        lisapistekoulutus = None,
        yleinenkoulumenestys = None,
        painotettavataineet = None,
        hakutoiveet = Seq(XMLHakutoive(
          hakukohdeOid = "hakukohde1",
          hakujno = 1,
          oppilaitos = "00999",
          opetuspiste = None,
          opetuspisteennimi = None,
          koulutus = "900",
          harkinnanvaraisuusperuste = None,
          urheilijanammatillinenkoulutus = None,
          yhteispisteet = None,
          valinta = None,
          vastaanotto = None,
          lasnaolo = None,
          terveys = None,
          aiempiperuminen = None,
          kaksoistutkinto = None,
          koulutuksenKieli = None
        )),
        None
      )
    )))

    val out: ByteArrayOutputStream = new ByteArrayOutputStream()
    ExcelUtilV1.write(out, hakijat)

    val wb: Workbook = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray))
    import scala.collection.JavaConversions._
    val result: IndexedSeq[((Int, Int), String)] = for (
      index <- 0 until wb.getNumberOfSheets;
      row <- wb.getSheetAt(index).toList;
      cell <- row.cellIterator().toList
    ) yield (row.getRowNum, cell.getColumnIndex) -> cell.getStringCellValue

    val resultMap = result.toMap

    resultMap.get((1,1)) should be (Some("1.1"))
    resultMap.get((1,7)) should be (Some("NotHelsinki"))
  }
}
