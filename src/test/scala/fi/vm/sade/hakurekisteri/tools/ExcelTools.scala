package fi.vm.sade.hakurekisteri.tools

import fi.vm.sade.javautils.poi.OphCellStyles.OphXssfCellStyles
import org.apache.poi.ss.usermodel.CellType.STRING
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.{XSSFSheet, XSSFWorkbook}

import scala.io.Source

trait ExcelTools {
  import scala.language.implicitConversions

  case class WorkbookData(sheets: (String, String)*) {

    case class RichWorkbook(workbook: XSSFWorkbook) {
      private val ophXssfCellStyles = new OphXssfCellStyles(workbook)

      def readData(data: String): Array[Array[String]] =
        Source.fromString(data.trim.stripMargin).getLines().toArray.map(_.split("\\|").map(_.trim))

      def addSheet(name: String)(data: String): Workbook = addSheet(name, readData(data))

      def addSheet(name: String, stringTable: Array[Array[String]]): XSSFWorkbook = {
        val sheet: XSSFSheet = workbook.createSheet(name)
        for ((row, index) <- stringTable.zipWithIndex) {
          val xslRow = sheet.createRow(index)
          for ((c, cellIndex) <- row.zipWithIndex) {
            val xssfCell = xslRow.createCell(cellIndex, STRING)
            xssfCell.setCellValue(c)
            ophXssfCellStyles.apply(xssfCell)
          }
        }
        workbook
      }
    }

    implicit def wb2rwb(workbook: XSSFWorkbook): RichWorkbook = RichWorkbook(workbook)

    val toExcel: Workbook = {
      val result = new XSSFWorkbook()
      for ((name, data) <- sheets) result.addSheet(name)(data)
      result
    }
  }
}
