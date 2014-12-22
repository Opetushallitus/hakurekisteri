package siirto

import org.scalatest.{FlatSpec, Matchers}
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import fi.vm.sade.hakurekisteri.tools.{ExcelTools, XmlEquality}

class ExcelConversionSpec  extends FlatSpec with Matchers with XmlEquality with ExcelTools {

  behavior of "Excel conversion"

  it should "convert excel into xml" in {
    val workbook: Workbook = new HSSFWorkbook()

    import ExcelReadConversions._

    implicit val xslFormat = defaultExcelFormat.withRoot(<data/>)

    workbook.addSheet("default")(
      """
        |column1|column2|column3
        |data11 |data12 |data13
        |data21 |data22 |data23
      """
    ).toXml should equal (
      <data>
        <default>
          <column1>data11</column1>
          <column2>data12</column2>
          <column3>data13</column3>
        </default>
        <default>
          <column1>data21</column1>
          <column2>data22</column2>
          <column3>data23</column3>
        </default>
      </data>
    )(after being normalized)
  }


}








