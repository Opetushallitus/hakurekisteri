package siirto

import fi.vm.sade.hakurekisteri.rest.support.Workbook
import fi.vm.sade.hakurekisteri.tools.{ExcelTools, XmlEquality}
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

    val henkilotElem: Elem = ArvosanatXmlConverter.converter.set(<henkilot/>, Workbook(wb))
    val doc: Elem = <arvosanat><eranTunniste>balaillaan</eranTunniste>{henkilotElem}</arvosanat>
    doc should equal (valid)(after being normalized)

    val validationResult: ValidationNel[(String, SAXParseException), Elem] = new ValidXml(Arvosanat, ArvosanatKoodisto).validate(doc)
    validationResult should equal(scalaz.Success(doc))
  }

  it should "group by hetu" in {
    val wb = WorkbookData(
      "perusopetus" ->
        """
          |HETU       |OPPIJANUMERO|HENKILOTUNNISTE|SYNTYMAAIKA|SUKUNIMI|ETUNIMET|KUTSUMANIMI|VALMISTUMINEN|MYONTAJA|SUORITUSKIELI|EIVALMISTU
          |111111-1975|            |               |           |Testi   |Test A  |Test       |31.05.2015   |05127   |FI           |
          |111111-1975|            |               |           |Testi   |Test A  |Test       |30.06.2015   |05127   |SV           |
        """
    ).toExcel

    val valid = <arvosanat>
      <henkilo>
        <hetu>111111-1975</hetu>
        <sukunimi>Testi</sukunimi>
        <etunimet>Test A</etunimet>
        <kutsumanimi>Test</kutsumanimi>
        <todistukset>
          <perusopetus>
            <valmistuminen>2015-05-31</valmistuminen>
            <myontaja>05127</myontaja>
            <suorituskieli>FI</suorituskieli>
          </perusopetus>
          <perusopetus>
            <valmistuminen>2015-06-30</valmistuminen>
            <myontaja>05127</myontaja>
            <suorituskieli>SV</suorituskieli>
          </perusopetus>
        </todistukset>
      </henkilo>
    </arvosanat>

    ArvosanatXmlConverter.converter.set(<arvosanat/>, Workbook(wb)) should equal (valid)(after being normalized)
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
}
