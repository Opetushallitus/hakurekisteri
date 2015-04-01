package siirto

import fi.vm.sade.hakurekisteri.rest.support.Workbook
import fi.vm.sade.hakurekisteri.tools.{ExcelTools, XmlEquality}
import org.apache.poi.ss.usermodel
import org.scalatest.{FlatSpec, Matchers}
import org.xml.sax.SAXParseException
import scala.xml.Elem
import scalaz.ValidationNel

class ArvosanatXmlConverterSpec extends FlatSpec with Matchers with XmlEquality with ExcelTools {
  behavior of "ArvosanatXMLConverter"

  it should "convert an arvosanat row with hetu into valid xml" in {
    val wb = WorkbookData(
      "perusopetus" ->
        """
          |HETU       |OPPIJANUMERO|HENKILOTUNNISTE|SYNTYMAAIKA|SUKUNIMI|ETUNIMET|KUTSUMANIMI|MYONTAJA|SUORITUSKIELI|VALMISTUMINEN|EIVALMISTU
          |111111-1975|            |               |           |Testi   |Test A  |Test       |05127   |FI           |31.05.2015   |
        """
    ).toExcel

    val valid = <arvosanat>
      <eranTunniste>balaillaan</eranTunniste>
      <henkilot>
        <henkilo>
          <hetu>111111-1975</hetu>
          <sukunimi>Testi</sukunimi>
          <etunimet>Test A</etunimet>
          <kutsumanimi>Test</kutsumanimi>
          <todistukset>
            <perusopetus>
              <myontaja>05127</myontaja>
              <suorituskieli>FI</suorituskieli>
              <valmistuminen>2015-05-31</valmistuminen>
            </perusopetus>
          </todistukset>
        </henkilo>
      </henkilot>
    </arvosanat>

    verifyConversion(wb, valid)
  }

  it should "group by hetu" in {
    val wb = WorkbookData(
      "perusopetus" ->
        """
          |HETU       |OPPIJANUMERO|HENKILOTUNNISTE|SYNTYMAAIKA|SUKUNIMI|ETUNIMET|KUTSUMANIMI|MYONTAJA|SUORITUSKIELI|VALMISTUMINEN|EIVALMISTU
          |111111-1975|            |               |           |Testi   |Test A  |Test       |05127   |FI           |31.05.2015   |
        """,
      "perusopetuksenlisaopetus" ->
        """
          |HETU       |OPPIJANUMERO|HENKILOTUNNISTE|SYNTYMAAIKA|SUKUNIMI|ETUNIMET|KUTSUMANIMI|MYONTAJA|SUORITUSKIELI|VALMISTUMINEN|EIVALMISTU
          |111111-1975|            |               |           |Testi   |Test A  |Test       |05127   |SV           |31.05.2015   |
        """
    ).toExcel

    val valid = <arvosanat>
      <eranTunniste>balaillaan</eranTunniste>
      <henkilot>
        <henkilo>
          <hetu>111111-1975</hetu>
          <sukunimi>Testi</sukunimi>
          <etunimet>Test A</etunimet>
          <kutsumanimi>Test</kutsumanimi>
          <todistukset>
            <perusopetus>
              <myontaja>05127</myontaja>
              <suorituskieli>FI</suorituskieli>
              <valmistuminen>2015-05-31</valmistuminen>
            </perusopetus>
            <perusopetuksenlisaopetus>
              <myontaja>05127</myontaja>
              <suorituskieli>SV</suorituskieli>
              <valmistuminen>2015-05-31</valmistuminen>
            </perusopetuksenlisaopetus>
          </todistukset>
        </henkilo>
      </henkilot>
    </arvosanat>

    verifyConversion(wb, valid)
  }

  it should "convert an arvosanat row with oppijanumero into valid xml" in {
    // TODO
  }

  it should "convert an arvosanat row with henkiloTunniste and syntymaAika into valid xml" in {
    // TODO
  }

  // TODO: arvosanat etc
  // TODO: perusopetuksenlisaopetus
  // TODO: eivalmistu
  // TODO: testaa esimerkkitiedostolla arvosanat.xls

  private def verifyConversion(wb: usermodel.Workbook, valid: Elem) {
    val henkilotElem: Elem = ArvosanatXmlConverter.converter.set(<henkilot/>, Workbook(wb))
    val doc: Elem = <arvosanat>
      <eranTunniste>balaillaan</eranTunniste>{henkilotElem}
    </arvosanat>
    doc should equal(valid)(after being normalized)

    val validationResult: ValidationNel[(String, SAXParseException), Elem] = new ValidXml(Arvosanat, ArvosanatKoodisto).validate(doc)
    validationResult should equal(scalaz.Success(doc))
  }

}
