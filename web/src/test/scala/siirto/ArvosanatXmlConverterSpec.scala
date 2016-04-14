package siirto

import fi.vm.sade.hakurekisteri.rest.support.Workbook
import fi.vm.sade.hakurekisteri.suoritus.DayFinder
import fi.vm.sade.hakurekisteri.tools.{ExcelTools, XmlEquality}
import org.apache.poi.ss.usermodel
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat
import org.scalatest.{FlatSpec, Matchers}
import org.xml.sax.SAXParseException

import scala.xml.{Elem, XML}
import scalaz.ValidationNel
import org.scalatest.matchers.{BeMatcher, MatchResult, Matcher}

class ArvosanatXmlConverterSpec extends FlatSpec with Matchers with XmlEquality with ExcelTools {
  behavior of "ArvosanatXMLConverter"

  def oletettuValmistuminen = DateTimeFormat.forPattern("yyyy-MM-dd").print(DayFinder.saturdayOfWeek22(LocalDate.now().getYear + 1))
  def oletettuValmistuminenFinDate = DateTimeFormat.forPattern("dd.MM.yyyy").print(DayFinder.saturdayOfWeek22(LocalDate.now().getYear + 1))

  it should "convert an arvosanat row with hetu into valid xml (jää luokalle)" in {
    val wb = WorkbookData(
      "perusopetus" ->
        s"""
          |HETU       |OPPIJANUMERO|HENKILOTUNNISTE|SYNTYMAAIKA|SUKUNIMI|ETUNIMET|KUTSUMANIMI|MYONTAJA|SUORITUSKIELI|VALMISTUMINEN|OLETETTUVALMISTUMINEN        |VALMISTUMINENSIIRTYY
          |111111-1975|            |               |           |Testi   |Test A  |Test       |05127   |FI           |             |$oletettuValmistuminenFinDate|JAA LUOKALLE
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
              <oletettuvalmistuminen>{oletettuValmistuminen}</oletettuvalmistuminen>
              <valmistuminensiirtyy>JAA LUOKALLE</valmistuminensiirtyy>
            </perusopetus>
          </todistukset>
        </henkilo>
      </henkilot>
    </arvosanat>

    wb should convertValidlyTo(valid)
  }


  it should "convert an arvosanat row with henkiloTunniste and syntymaAika into valid xml (ei valmistu)" in {
    val wb = WorkbookData(
      "perusopetus" ->
        """
          |HETU       |OPPIJANUMERO|HENKILOTUNNISTE|SYNTYMAAIKA|SUKUNIMI|ETUNIMET|KUTSUMANIMI|MYONTAJA|SUORITUSKIELI|OPETUSPAATTYNYT|EIVALMISTU
          |           |            |1234           |1.1.1976   |Testi   |Test A  |Test       |05127   |FI           |31.5.2015      |PERUSOPETUS PAATTYNYT VALMISTUMATTA
        """
    ).toExcel

    val valid = <arvosanat>
      <eranTunniste>balaillaan</eranTunniste>
      <henkilot>
        <henkilo>
          <henkiloTunniste>1234</henkiloTunniste>
          <syntymaAika>1976-01-01</syntymaAika>
          <sukunimi>Testi</sukunimi>
          <etunimet>Test A</etunimet>
          <kutsumanimi>Test</kutsumanimi>
          <todistukset>
            <perusopetus>
              <myontaja>05127</myontaja>
              <suorituskieli>FI</suorituskieli>
              <opetuspaattynyt>2015-05-31</opetuspaattynyt>
              <eivalmistu>PERUSOPETUS PAATTYNYT VALMISTUMATTA</eivalmistu>
            </perusopetus>
          </todistukset>
        </henkilo>
      </henkilot>
    </arvosanat>

    wb should convertValidlyTo(valid)
  }

  it should "group by hetu (10. luokka ei valmistu)" in {
    val wb = WorkbookData(
      "perusopetus" ->
        """
          |HETU       |OPPIJANUMERO|HENKILOTUNNISTE|SYNTYMAAIKA|SUKUNIMI|ETUNIMET|KUTSUMANIMI|MYONTAJA|SUORITUSKIELI|VALMISTUMINEN|EIVALMISTU
          |111111-1975|            |               |           |Testi   |Test A  |Test       |05127   |FI           |31.05.2015   |
        """,
      "perusopetuksenlisaopetus" ->
        """
          |HETU       |OPPIJANUMERO|HENKILOTUNNISTE|SYNTYMAAIKA|SUKUNIMI|ETUNIMET|KUTSUMANIMI|MYONTAJA|SUORITUSKIELI|VALMISTUMINEN  |EIVALMISTU
          |111111-1975|            |               |           |Testi   |Test A  |Test       |05127   |SV           |31.05.2015     |SUORITUS HYLATTY
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
              <eivalmistu>SUORITUS HYLATTY</eivalmistu>
            </perusopetuksenlisaopetus>
          </todistukset>
        </henkilo>
      </henkilot>
    </arvosanat>

    wb should convertValidlyTo(valid)
  }

  it should "convert an arvosanat row with oppijanumero into valid xml" in {
    val wb = WorkbookData(
      "perusopetus" ->
        """
          |HETU       |OPPIJANUMERO              |HENKILOTUNNISTE|SYNTYMAAIKA|SUKUNIMI|ETUNIMET|KUTSUMANIMI|MYONTAJA|SUORITUSKIELI|VALMISTUMINEN|EIVALMISTU
          |           |1.2.246.562.24.14229104472|               |           |Testi   |Test A  |Test       |05127   |FI           |31.05.2015   |
        """
    ).toExcel

    convertXls(wb) should be (valid)
  }

  it should "konvertoi aineiden arvosanat" in {
    val wb = WorkbookData(
      "perusopetus" ->
        """
          |HETU       |OPPIJANUMERO|HENKILOTUNNISTE|SYNTYMAAIKA|SUKUNIMI|ETUNIMET|KUTSUMANIMI|MYONTAJA|SUORITUSKIELI|VALMISTUMINEN|AI_YH|AI_VAL|AI_VAL2|AI_TYYPPI|A1_YH|A1_VAL|A1_VAL2|A1_KIELI|B23_YH |B23_KIELI|MA_YH
          |111111-1975|            |               |           |Testi   |Test A  |Test       |05127   |FI           |31.05.2015   |    9|     8|      7|FI       |6    | 5    | 4     |SV      |     4 |FR       |10
        """,
      "perusopetuksenlisaopetus" ->
        """
          |HETU       |OPPIJANUMERO|HENKILOTUNNISTE|SYNTYMAAIKA|SUKUNIMI|ETUNIMET|KUTSUMANIMI|MYONTAJA|SUORITUSKIELI|VALMISTUMINEN  |AI_YH | AI_TYYPPI
          |111111-1975|            |               |           |Testi   |Test A  |Test       |05127   |SV           |31.05.2015     |9     | SV
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
              <AI>
                <yhteinen>9</yhteinen>
                <valinnainen>8</valinnainen>
                <valinnainen>7</valinnainen>
                <tyyppi>FI</tyyppi>
              </AI>
              <A1>
                <yhteinen>6</yhteinen>
                <valinnainen>5</valinnainen>
                <valinnainen>4</valinnainen>
                <kieli>SV</kieli>
              </A1>
              <B23>
                <yhteinen>4</yhteinen>
                <kieli>FR</kieli>
              </B23>
              <MA>
                <yhteinen>10</yhteinen>
              </MA>
            </perusopetus>
            <perusopetuksenlisaopetus>
              <myontaja>05127</myontaja>
              <suorituskieli>SV</suorituskieli>
              <valmistuminen>2015-05-31</valmistuminen>
              <AI>
                <yhteinen>9</yhteinen>
                <tyyppi>SV</tyyppi>
              </AI>
            </perusopetuksenlisaopetus>
          </todistukset>
        </henkilo>
      </henkilot>
    </arvosanat>

    wb should convertValidlyTo(valid)
  }

  it should "konvertoi lukion arvosanat" in {
    val wb = WorkbookData(
      "lukio" ->
        """
          |HETU       |OPPIJANUMERO|HENKILOTUNNISTE|SYNTYMAAIKA|SUKUNIMI|ETUNIMET|KUTSUMANIMI|MYONTAJA|SUORITUSKIELI|VALMISTUMINEN|AI_YH  |AI_TYYPPI|A1_YH|A1_KIELI|B23_YH|B23_KIELI|MA_YH|MA_LAAJUUS|
          |111111-1975|            |               |           |Testi   |Test A  |Test       |05127   |FI           |31.05.2015   |  9    |FI       |6    |SV      | 4    |FR       |10   |pitka     |
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
            <lukio>
              <myontaja>05127</myontaja>
              <suorituskieli>FI</suorituskieli>
              <valmistuminen>2015-05-31</valmistuminen>
              <AI>
                <yhteinen>9</yhteinen>
                <tyyppi>FI</tyyppi>
              </AI>
              <A1>
                <yhteinen>6</yhteinen>
                <kieli>SV</kieli>
              </A1>
              <B23>
                <yhteinen>4</yhteinen>
                <kieli>FR</kieli>
              </B23>
              <MA>
                <yhteinen>10</yhteinen>
                <laajuus>pitka</laajuus>
              </MA>
            </lukio>
          </todistukset>
        </henkilo>
      </henkilot>
    </arvosanat>

    wb should convertValidlyTo(valid)
  }

  it should "convert ammattistartti" in {
    convertXls(lisaopetusExcel("ammattistartti")) should be (valid)
  }

  it should "convert valmentava" in {
    convertXls(lisaopetusExcel("valmentava")) should be (valid)
  }

  it should "convert valma" in {
    convertXls(lisaopetusExcel("valma")) should be (valid)
  }

  it should "convert telma" in {
    convertXls(lisaopetusExcel("telma")) should be (valid)
  }

  it should "convert maahanmuuttajienammvalmistava" in {
    convertXls(lisaopetusExcel("maahanmuuttajienammvalmistava")) should be (valid)

  }




  private def lisaopetusExcel(todistusType: String) = WorkbookData(
    todistusType ->
      """
        |HETU       |OPPIJANUMERO|HENKILOTUNNISTE|SYNTYMAAIKA|SUKUNIMI|ETUNIMET|KUTSUMANIMI|MYONTAJA|SUORITUSKIELI|VALMISTUMINEN  |AI_YH |AI_TYYPPI
        |111111-1975|            |               |           |Testi   |Test A  |Test       |05127   |SV           |31.05.2015     |9     |SV
      """
  ).toExcel

  it should "convert arvosanat.xls into valid xml" in {
    XML.load(getClass.getResource("/arvosanat-test.xml")) should be (valid) // sanity check
    val doc: Elem = ArvosanatXmlConverter.convert(getClass.getResourceAsStream("/arvosanat-test.xls"), "arvosanat.xml")

    doc should be (valid)

  }

  private def eivalmistuLisaopetusExcel(todistusType: String) = WorkbookData(
    todistusType ->
      """
        |HETU       |OPPIJANUMERO|HENKILOTUNNISTE|SYNTYMAAIKA|SUKUNIMI|ETUNIMET|KUTSUMANIMI|MYONTAJA|SUORITUSKIELI|VALMISTUMINEN  |AI_YH |AI_TYYPPI|EIVALMISTU
        |111111-1975|            |               |           |Testi   |Test A  |Test       |05127   |SV           |31.05.2015     |9     |SV       |SUORITUS HYLATTY
      """
  ).toExcel

  it should "convert eivalmistu perusopetuksenlisaopetus xls into valid xml" in {
    convertXls(eivalmistuLisaopetusExcel("perusopetuksenlisaopetus")) should be (valid)
  }


  def convertValidlyTo(expected:Elem): Matcher[usermodel.Workbook] = (equal(expected)(after being normalized) and be (valid)) compose (convertXls)

  val valid =  BeMatcher[Elem]{e =>
    val validationResult: ValidationNel[(String, SAXParseException), Elem] = new ValidXml(ArvosanatV2, ArvosanatKoodisto).validate(e)
    MatchResult(
      validationResult == scalaz.Success(e),
      s"""elem is not valid""",
      s"""elem is valid""""

    )
  }



  def convertXls(wb: usermodel.Workbook): Elem = {
    ArvosanatXmlConverter.convert(Workbook(wb), "balaillaan")
  }
}
