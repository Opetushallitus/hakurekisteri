package fi.vm.sade.hakurekisteri.tools

import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.CellType.STRING
import org.apache.poi.ss.usermodel.{Sheet, Workbook}

import scala.io.Source

trait ExcelTools {
  import scala.language.implicitConversions

  case class WorkbookData(sheets: (String, String)*) {

    case class RichWorkbook(workbook: Workbook) {

      def readData(data: String): Array[Array[String]] =
        Source.fromString(data.trim.stripMargin).getLines().toArray.map(_.split("\\|").map(_.trim))

      def addSheet(name: String)(data: String): Workbook = addSheet(name, readData(data))

      def addSheet(name: String, stringTable: Array[Array[String]]): Workbook = {
        val sheet: Sheet = workbook.createSheet(name)
        for (
          (row, index) <- stringTable.zipWithIndex
        ) {
          val xslRow = sheet.createRow(index)
          for (
            (cell, cellIndex) <- row.zipWithIndex
          ) xslRow.createCell(cellIndex, STRING).setCellValue(cell)
        }
        workbook
      }
    }

    implicit def wb2rwb(workbook: Workbook): RichWorkbook = RichWorkbook(workbook)

    val toExcel: Workbook = {
      val result = new HSSFWorkbook()
      for (
        (name, data) <- sheets
      ) result.addSheet(name)(data)
      result
    }
  }
}
