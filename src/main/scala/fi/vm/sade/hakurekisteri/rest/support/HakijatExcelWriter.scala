package fi.vm.sade.hakurekisteri.rest.support

import java.io.OutputStream

import org.apache.poi.hssf.{usermodel => hssf}


import org.apache.poi.ss.{usermodel => poi}

case class Cell(index: Int, value: String)
case class Row(index: Int, cells: Set[Cell])
case class Sheet(name: String, rows: Set[Row])

object Row {
  def apply(index: Int)(cells: Cell*): Row = Row(index, cells.toSet)
}

object StringCell{
  def apply(i: Int, v: String) = Cell(i,v)
}

class Workbook(val sheets: Set[Sheet]) {
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
    val sheets  = for (
      index <- 0 until original.getNumberOfSheets
    ) yield {
      val os = original.getSheetAt(index)
      import scala.collection.JavaConversions._
      Sheet(os.getSheetName, os.map((row) => Row(row.getRowNum, row.map((cell) => Cell(cell.getColumnIndex, cell.getStringCellValue)).toSet)).toSet)
    }
    new Workbook(sheets.toSet)
  }
}



trait HakijatExcelWriter[T] {

  val zero = BigDecimal.valueOf(0)




  def getHeaders: Set[Row]

  def getRows(hakijat: T): Set[Row]

  def write(out: OutputStream, hakijat: T): Unit = {
    val sheet = Sheet("Hakijat", getHeaders ++ getRows(hakijat))
    val wb = new Workbook(Set(sheet))
    wb.writeTo(out)
  }

  def toBooleanX(v: Boolean): String = if (v) "X" else ""

  def toBooleanX(v: Option[Boolean]): String = if (v.getOrElse(false)) "X" else ""

}
