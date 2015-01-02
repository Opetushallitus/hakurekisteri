package fi.vm.sade.hakurekisteri.tools


import org.apache.poi.ss.{usermodel => poi}

import scala.io.Source
import org.apache.poi.hssf.usermodel.HSSFWorkbook

trait ExcelTools {



  import scala.language.implicitConversions




  case class WorkbookData(sheets: (String, String)*)  {

    case class RichWorkbook(workbook:poi.Workbook) {


      def readData(data:String): Array[Array[String]] =
        Source.fromString(data.trim.stripMargin).getLines().toArray.map(_.split("\\|").map(_.trim))

      def addSheet(name:String)(data:String): poi.Workbook = addSheet(name, readData(data))
      def addSheet(name:String, stringTable: Array[Array[String]]): poi.Workbook = {
        val sheet: poi.Sheet = workbook.createSheet(name)
        for (
          (row, index) <- stringTable.zipWithIndex
        ) {
          val xslRow = sheet.createRow(index)
          for (
            (cell, cellIndex) <- row.zipWithIndex
          ) xslRow.createCell(cellIndex, org.apache.poi.ss.usermodel.Cell.CELL_TYPE_STRING).setCellValue(cell)
        }
        workbook
      }
    }

    implicit def wb2rwb(workbook:poi.Workbook): RichWorkbook = RichWorkbook(workbook)

    val toExcel:poi.Workbook = {
      val result = new HSSFWorkbook()
      for (
        (name, data) <- sheets
      ) result.addSheet(name)(data)
      result
    }
  }

}
