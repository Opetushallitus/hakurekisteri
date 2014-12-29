package siirto

import org.apache.poi.ss.usermodel.{Sheet, Workbook}
import scala.xml.Elem

import scala.language.implicitConversions


object ExcelReadConversions {

  implicit def wb2XmlConversion(workbook: Workbook): ReadConvertibleExcel = ReadConvertibleExcel(workbook)

  val defaultExcelFormat = DefaultExcelFormat()
}

case class Sheet(name: String, data: Array[Array[(String, String)]])


case class DefaultExcelFormat(root: Elem = <defaultRoot/>,  handleSheet: PartialFunction[(Elem, Sheet), Elem] = Map()) extends ExcelFormat {
  import scala.collection.JavaConversions._

  def readSheet(sheet: org.apache.poi.ss.usermodel.Sheet): Sheet =  {
    val data = (for (
      row <- sheet.rowIterator()
    ) yield (for (
        cell <- row.cellIterator()
      ) yield cell.getStringCellValue).toArray).toArray.splitAt(1) match {
      case (nestedHeaders, data) => for (
        dataRow <- data
      ) yield for (
          (dataCell, columnIndex) <- dataRow.zipWithIndex
        ) yield nestedHeaders(0)(columnIndex) -> dataCell
    }
    Sheet(sheet.getSheetName, data)
  }



  def defaultHandleSheet(currentXml: Elem, sheet: Sheet): Elem = {
    val content = for (
      row: Array[(String, String)] <- sheet.data

    ) yield {
      val rowContent = for (
        (itemName, item) <- row
      ) yield <xml>{item}</xml>.copy(label = itemName)
      <xml>{rowContent}</xml>.copy(label = sheet.name)
    }
    currentXml.copy(child = currentXml.child ++ content)

  }

  override def toXml(workbook: Workbook): Elem = {
    val sheets = for (i <- 0 until workbook.getNumberOfSheets) yield workbook.getSheetAt(i)
    sheets.map(readSheet).foldLeft(root)((elem, sheet) => handleSheet.applyOrElse[(Elem, Sheet), Elem]((elem, sheet), (defaultHandleSheet _).tupled))
  }

  override def withRoot(root: Elem) = this.copy(root = root)

  override def withHandlers(handler: PartialFunction[(Elem, Sheet), Elem]) = this.copy(handleSheet = handler)
}

case class ReadConvertibleExcel(workbook: Workbook) extends ExcelReadConversions {
}

trait ExcelReadConversions {

  val workbook: Workbook

  def toXml(implicit reader: ExcelFormat):Elem = reader.toXml(workbook)

}

trait ExcelFormat {

  def toXml(workbook:Workbook): Elem

  def withRoot(root:Elem): ExcelFormat

  def withHandlers(handler: PartialFunction[(Elem, Sheet), Elem]): ExcelFormat


}