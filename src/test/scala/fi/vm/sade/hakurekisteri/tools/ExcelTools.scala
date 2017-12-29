package fi.vm.sade.hakurekisteri.tools

import fi.vm.sade.javautils.poi.OphCellStyles.OphHssfCellStyles
import org.apache.poi.hssf.usermodel.{HSSFSheet, HSSFWorkbook}
import org.apache.poi.ss.usermodel.CellType.STRING
import org.apache.poi.ss.usermodel.{Sheet, Workbook}

import scala.io.Source

trait ExcelTools {
  import scala.language.implicitConversions

  case class WorkbookData(sheets: (String, String)*) {

    case class RichWorkbook(workbook: HSSFWorkbook) {
      private val ophHssfCellStyles = new OphHssfCellStyles(workbook)

      def readData(data: String): Array[Array[String]] =
        Source.fromString(data.trim.stripMargin).getLines().toArray.map(_.split("\\|").map(_.trim))

      def addSheet(name: String)(data: String): Workbook = addSheet(name, readData(data))

      def addSheet(name: String, stringTable: Array[Array[String]]): HSSFWorkbook = {
        val sheet: HSSFSheet = workbook.createSheet(name)
        for (
          (row, index) <- stringTable.zipWithIndex
        ) {
          val xslRow = sheet.createRow(index)
          for (
            (c, cellIndex) <- row.zipWithIndex
          ) {
            val hssfCell = xslRow.createCell(cellIndex, STRING)
            hssfCell.setCellValue(c)
            ophHssfCellStyles.apply(hssfCell)
          }
        }
        workbook
      }
    }

    implicit def wb2rwb(workbook: HSSFWorkbook): RichWorkbook = RichWorkbook(workbook)

    val toExcel: Workbook = {
      val result = new HSSFWorkbook()
      for (
        (name, data) <- sheets
      ) result.addSheet(name)(data)
      result
    }
  }
}
