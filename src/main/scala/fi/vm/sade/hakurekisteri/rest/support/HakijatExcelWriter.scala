package fi.vm.sade.hakurekisteri.rest.support

import java.io.OutputStream

import org.apache.poi.hssf.{usermodel => hssf}


import org.apache.poi.ss.{usermodel => poi}
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat
import org.scalatra.servlet.FileItem

import scala.language.implicitConversions
import scala.util.matching.Regex

case class Cell(index: Int, value: String)
case class Row(index: Int, cells: Set[Cell])
case class Sheet(name: String, rows: Set[Row])

object Row {
  def apply(index: Int)(cells: Cell*): Row = Row(index, cells.toSet)
}

object StringCell{
  def apply(i: Int, v: String) = Cell(i,v)
}

class Workbook(val sheets: Seq[Sheet]) {
  def writeTo(out: OutputStream) {
    val workbook = toExcel
    workbook.write(out)
  }

  def toExcel: poi.Workbook = {
    val workbook = new hssf.HSSFWorkbook()

    for (sheet <- sheets) {
      val eSheet = workbook.createSheet(sheet.name)
      for(row <- sheet.rows) {
        val eRow = eSheet.createRow(row.index)
        for (cell <- row.cells) {
          eRow.createCell(cell.index, org.apache.poi.ss.usermodel.Cell.CELL_TYPE_STRING).setCellValue(cell.value)
        }
      }
    }
    workbook
  }
}

object Workbook {

  def apply(original: poi.Workbook): Workbook = {

    implicit def cellToString(cell: poi.Cell): String = cell.getCellType match {
      case poi.Cell.CELL_TYPE_STRING =>
        cell.getStringCellValue
      case poi.Cell.CELL_TYPE_BLANK => ""
      case poi.Cell.CELL_TYPE_BOOLEAN => cell.getBooleanCellValue.toString
      case poi.Cell.CELL_TYPE_ERROR => throw new Exception("error in excel")
      case poi.Cell.CELL_TYPE_FORMULA => throw new Exception("Formulas not supported")
      case poi.Cell.CELL_TYPE_NUMERIC if poi.DateUtil.isCellDateFormatted(cell) =>
        val d = new LocalDate(cell.getDateCellValue)
        DateTimeFormat.forPattern(LocalDateSerializer.dayFormat).print(d)
      case poi.Cell.CELL_TYPE_NUMERIC =>
        val df = new poi.DataFormatter()
        df.createFormat(cell).format(cell.getNumericCellValue)
      case cellType => throw new Exception(s"unknown cell type $cellType")
    }

    val sheets  = for (
      index <- 0 until original.getNumberOfSheets
    ) yield {
      val os = original.getSheetAt(index)
      import scala.collection.JavaConversions._
      val readRow: (poi.Row) => Row = (row) => {
        Row(row.getRowNum, row.map((cell) => Cell(cell.getColumnIndex, cell)).toSet)
      }
      try {
        Sheet(os.getSheetName, os.map(readRow).toSet)
      } catch {
        case e: Throwable => e.printStackTrace()
          throw e
      }

    }
    new Workbook(sheets)

  }

  def apply(f: FileItem): Workbook = {
    apply(poi.WorkbookFactory.create(f.getInputStream))
  }
}



trait HakijatExcelWriter[T] {

  val zero = BigDecimal.valueOf(0)




  def getHeaders: Set[Row]

  def getRows(hakijat: T): Set[Row]

  def write(out: OutputStream, hakijat: T): Unit = {
    val sheet = Sheet("Hakijat", getHeaders ++ getRows(hakijat))
    val wb = new Workbook(Seq(sheet))
    wb.writeTo(out)
  }

  def toBooleanX(v: Boolean): String = if (v) "X" else ""

  def toBooleanX(v: Option[Boolean]): String = if (v.getOrElse(false)) "X" else ""

}
