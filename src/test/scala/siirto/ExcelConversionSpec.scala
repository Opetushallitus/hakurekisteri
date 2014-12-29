package siirto

import org.scalatest.{FlatSpec, Matchers}
import fi.vm.sade.hakurekisteri.tools.{ExcelTools, XmlEquality}
import siirto.ExcelReadConversions._
import scala.xml.{Node, Text, NodeSeq, Elem}

class ExcelConversionSpec  extends FlatSpec with Matchers with XmlEquality with ExcelTools {

  behavior of "Excel conversion"

  import ExcelReadConversions._

  it should "convert excel into xml" in {


    implicit val xslFormat = defaultExcelFormat.withRoot(<data/>)
    Workbook(
      "default" ->
        """
          |column1|column2|column3
          |data11 |data12 |data13
          |data21 |data22 |data23
        """
    ).toExcel.toXml should equal (
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

  it should "handle multiple sheets" in {

    implicit val xslFormat = defaultExcelFormat.withRoot(<data/>)
    Workbook(
      "default" ->
        """
          |column1|column2|column3
          |data11 |data12 |data13
          |data21 |data22 |data23
        """,
      "default2" ->
        """
          |column1|column2|column3
          |data11 |data12 |data13
          |data21 |data22 |data23
        """
    ).toExcel.toXml should equal (
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
        <default2>
          <column1>data11</column1>
          <column2>data12</column2>
          <column3>data13</column3>
        </default2>
        <default2>
          <column1>data21</column1>
          <column2>data22</column2>
          <column3>data23</column3>
        </default2>
      </data>
    )(after being normalized)

  }

  it should "use custom given custom conversion for worksheet" in {

    def withExisting(currentXml: Elem, row: Array[(String, String)])(collector: (Array[(String, String)], Elem) => Elem): Elem = {
      val id = row.collectFirst {
        case ("id", id) => id
      }.get


      val (matched, others) = (currentXml \ "item").partition(
        (node) => (node \ "id").head.child.headOption.fold(false)((item) => item == Text(id))
      )
      val existing: Option[Elem] =
        matched.headOption.collect{case e: Elem => e}


      val updated: Elem = {
        val original = existing.getOrElse(<item>
          <id>{id}</id>
        </item>)
        collector(row.filterNot(_._1 == "id"), original)
      }

      val items = others ++ updated

      <data>{items}</data>
    }

    def updateRow(data: Array[Array[(String, String)]], currentXml: Elem, sheetElem: Elem) = data.foldLeft(currentXml) (withExisting(_, _){
      (newData, itemElement) =>
        val newCells = newData.map{
          case (label, value) => <tag>{value}</tag>.copy(label = label)
        }
        itemElement.copy(child = itemElement.child ++ sheetElem.copy(child = newCells))

    })

    val handler: PartialFunction[(Elem, Sheet), Elem] = {

      case (currentXml, Sheet("sheet1", data)) => updateRow(data, currentXml, <sheet1/>)
      case (currentXml, Sheet("sheet2", data)) => updateRow(data, currentXml, <sheet2/>)

    }

    implicit val xslFormat = defaultExcelFormat.withRoot(<data/>).withHandlers(handler)




    Workbook(
      "sheet1" ->
        """
          |id     |column2|column3
          |id1    |data12 |data13
          |id2    |data22 |data23
        """,
      "sheet2" ->
        """
          |id     |column2 |column3
          |id1    |data212 |data213
          |id2    |data222 |data223
        """
    ).toExcel.toXml should equal (
      <data>
        <item>
          <id>id1</id>
            <sheet1>
              <column2>data12</column2>
              <column3>data13</column3>
            </sheet1>
            <sheet2>
              <column2>data212</column2>
              <column3>data213</column3>
            </sheet2>
        </item>
        <item>
          <id>id2</id>
            <sheet1>
              <column2>data22</column2>
              <column3>data23</column3>
            </sheet1>
            <sheet2>
              <column2>data222</column2>
              <column3>data223</column3>
            </sheet2>
        </item>
      </data>
    )(after being normalized)




  }

}









