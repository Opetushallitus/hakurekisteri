package fi.vm.sade.hakurekisteri.tools

import org.apache.poi.ss.usermodel.{Sheet, Workbook}
import scala.io.Source

/**
 * Created by verneri on 22.12.14.
 */
trait ExcelTools {

  case class RichWorkbook(workbook:Workbook) {


    def readData(data:String): Array[Array[String]] =
      Source.fromString(data.trim.stripMargin).getLines().toArray.map(_.split("\\|").map(_.trim))

    def addSheet(name:String)(data:String): Workbook = addSheet(name, readData(data))
    def addSheet(name:String, stringTable: Array[Array[String]]): Workbook = {
      val sheet: Sheet = workbook.createSheet(name)
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

  import scala.language.implicitConversions


  implicit def wb2rwb(workbook:Workbook): RichWorkbook = RichWorkbook(workbook)
}
