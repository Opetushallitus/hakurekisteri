package fi.vm.sade.hakurekisteri.rest.support

import java.io.OutputStream

import fi.vm.sade.javautils.poi.OphCellStyles.OphHssfCellStyles
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.CellType.BLANK
import org.apache.poi.ss.usermodel.CellType.BOOLEAN
import org.apache.poi.ss.usermodel.CellType.ERROR
import org.apache.poi.ss.usermodel.CellType.FORMULA
import org.apache.poi.ss.usermodel.CellType.NUMERIC
import org.apache.poi.ss.usermodel.CellType.STRING
import org.apache.poi.ss.{usermodel => poi}
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat

import scala.language.implicitConversions

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
    val workbook = new HSSFWorkbook()
    val ophHssfCellStyles = new OphHssfCellStyles(workbook)

    for (sheet <- sheets) {
      val eSheet = workbook.createSheet(sheet.name)
      for(row <- sheet.rows) {
        val eRow = eSheet.createRow(row.index)
        for (cell <- row.cells) {
          val eCell = eRow.createCell(cell.index, STRING)
          eCell.setCellValue(cell.value)
          ophHssfCellStyles.apply(eCell)
        }
      }
    }
    workbook
  }
}

object Workbook {

  def apply(original: poi.Workbook): Workbook = {

    implicit def cellToString(cell: poi.Cell): String = cell.getCellTypeEnum match {
      case STRING =>
        cell.getStringCellValue
      case BLANK => ""
      case BOOLEAN => cell.getBooleanCellValue.toString
      case ERROR => throw new Exception("error in excel")
      case FORMULA => throw new Exception("Formulas not supported")
      case NUMERIC if poi.DateUtil.isCellDateFormatted(cell) =>
        val d = new LocalDate(cell.getDateCellValue)
        DateTimeFormat.forPattern(LocalDateSerializer.dayFormat).print(d)
      case NUMERIC =>
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

}



trait HakijatExcelWriter[T] {

  val zero = BigDecimal.valueOf(0)




  def getHeaders(hakijat: T): Set[Row]

  def getRows(hakijat: T): Set[Row]

  def write(out: OutputStream, hakijat: T): Unit = {
    val sheet = Sheet("Hakijat", getHeaders(hakijat) ++ getRows(hakijat))
    val wb = new Workbook(Seq(sheet))
    wb.writeTo(out)
  }

  def toBooleanX(v: Boolean): String = if (v) "X" else ""

  def toBooleanX(v: Option[Boolean]): String = if (v.getOrElse(false)) "X" else ""

}

trait HakijatExcelWriterV3[T] {

  val zero = BigDecimal.valueOf(0)

  def getRows(hakijat: T): Set[Row]

  def write(out: OutputStream, hakijat: T): Unit = {
    val sheet = Sheet("Hakijat", getRows(hakijat))
    val wb = new Workbook(Seq(sheet))
    wb.writeTo(out)
  }

  def toBooleanX(v: Boolean): String = if (v) "X" else ""

  def toBooleanX(v: Option[Boolean]): String = if (v.getOrElse(false)) "X" else ""

}
