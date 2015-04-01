package siirto

import fi.vm.sade.hakurekisteri.rest.support.Workbook
import fi.vm.sade.hakurekisteri.tools.{ExcelTools, XmlEquality}
import org.scalatest.{FlatSpec, Matchers}


class ArvosanatXmlConverterSpec extends FlatSpec with Matchers with XmlEquality with ExcelTools {
  behavior of "ArvosanatXMLConverter"

  it should "convert an arvosanat row with hetu into valid xml" in {
    val wb = WorkbookData(
      "perusopetus" ->
        """
          |HETU       |OPPIJANUMERO|HENKILOTUNNISTE|SYNTYMAAIKA|SUKUNIMI|ETUNIMET|KUTSUMANIMI|VALMISTUMINEN|MYONTAJA|SUORITUSKIELI|EIVALMISTU
          |111111-1975|            |               |           |Testi   |Test A  |Test       |31.05.2015   |05127   |FI           |
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
        </todistukset>
      </henkilo>
    </arvosanat>

    ArvosanatXmlConverter.converter.set(<arvosanat/>, Workbook(wb)) should equal (valid)(after being normalized)
  }

  it should "convert an arvosanat row with oppijanumero into valid xml" in {

  }

  it should "convert an arvosanat row with henkiloTunniste and syntymaAika into valid xml" in {

  }
}
