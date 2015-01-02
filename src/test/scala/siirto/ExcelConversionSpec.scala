package siirto

import org.scalatest.{FlatSpec, Matchers}
import fi.vm.sade.hakurekisteri.tools.{ExcelTools, XmlEquality}
import scala.xml.{Elem, Node}
import siirto.DataCollectionConversions._
import fi.vm.sade.hakurekisteri.rest.support.Workbook
import scala.xml.transform.RewriteRule
import siirto.DataCollectionConversions.DataCell
import scalaz._
import siirto.DataCollectionConversions.DataCell
import siirto.ExcelConversions.ExcelExtractor.RowHandler

class ExcelConversionSpec  extends FlatSpec with Matchers with XmlEquality with ExcelTools {

  behavior of "Excel conversion"

  import ExcelConversions._

  it should "convert excel into xml" in {

    val rowWriter: RowWriter = (elem,row) =>
      elem.copy(child = elem.child ++ Seq(<default>{row.map((cell) => <tag>{cell.value}</tag>.copy(label = cell.name))}</default>))


    val reader: CollectionReader = (elem) => (elem \ "default").map(_.child.map(node => DataCell(node.label, node.text)))

    val converter = ExcelExtractor(
      "default" -> (rowWriter, reader)
    )


    val wb = WorkbookData(
      "default" ->
        """
          |column1|column2|column3
          |data11 |data12 |data13
          |data21 |data22 |data23
        """
    ).toExcel

    converter.set(<data/>, Workbook(wb)) should equal (
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

   val wb = WorkbookData(
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
    ).toExcel

   def rowWriter(itemName: String): RowWriter = (elem,row) =>
     elem.copy(child = elem.child ++ Seq(<default>{row.map((cell) => <tag>{cell.value}</tag>.copy(label = cell.name))}</default>.copy(label = itemName)))


   def reader(itemName: String): CollectionReader = (elem) => (elem \ itemName).map(_.child.map(node => DataCell(node.label, node.text)))

   val converter = ExcelExtractor(
     "default" -> (rowWriter("default"), reader("default")),
     "default2" -> (rowWriter("default2"), reader("default2"))

   )

   converter.set(<data/>, Workbook(wb)) should equal (
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

  it  should "use custom given custom conversion for worksheet" in {


    val wb = WorkbookData(
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
    ).toExcel

    def itemIdentity(item: Elem): Elem = {
      item.copy(child = item \ "id")
    }

    def addIdentity(row: DataRow, nodes: Seq[Node]): Seq[Node] = {
      val id = row.collectFirst{case DataCell("id", id) => id}.get
      nodes match {
        case nodes if !nodes.exists((idNode) => idNode.label == "id") => nodes ++ <id>{id}</id>
        case default => default
      }
    }

    val sheet1Lens: Elem @> DataRow = Lens.lensu(
      (item, row) =>
        {
          val sheetData = <sheet1>
            {row.collect {
              case DataCell(name, value) if Set("column2", "column3").contains(name) => <tag>
                {value}
              </tag>.copy(label = name)
            }}
          </sheet1>
          item.copy(child = addIdentity(row, item.child) ++ sheetData)
        }
        ,
      (item) => Seq()
    )

    val sheet2Lens: Elem @> DataRow = Lens.lensu(
      (item, row) => {
        val sheetData = <sheet2>
          {row.collect {
            case DataCell(name, value) if Set("column2", "column3").contains(name) => <tag>
              {value}
            </tag>.copy(label = name)
          }}
        </sheet2>
        val result = item.copy(child = addIdentity(row, item.child) ++ sheetData)
        result
      },
      (item) => Seq()
    )

    val converter: WorkBookExtractor = ExcelExtractor(itemIdentity _, <item/>)(
      "sheet1" -> sheet1Lens,
      "sheet2" -> sheet2Lens
    )

    converter.set(<data/>, Workbook(wb)) should equal (
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









