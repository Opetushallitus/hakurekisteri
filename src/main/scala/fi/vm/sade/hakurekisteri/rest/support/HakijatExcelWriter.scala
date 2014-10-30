package fi.vm.sade.hakurekisteri.rest.support

import java.io.OutputStream

import org.apache.poi.hssf.usermodel.HSSFWorkbook

trait HakijatExcelWriter[T] {

  val zero = BigDecimal.valueOf(0)

  case class Cell(index: Int, value: String)
  case class Row(index: Int, cells: Set[Cell])
  case class Sheet(name: String, rows: Set[Row])

  object Row {
    def apply(index: Int)(cells: Cell*): Row = Row(index, cells.toSet)
  }

  object StringCell{
    def apply(i: Int, v: String) = Cell(i,v)
  }

  class Workbook(sheets: Set[Sheet]) {
    def writeTo(out: OutputStream) {
      val workbook = new HSSFWorkbook()

      for (sheet <- sheets) {
        val eSheet = workbook.createSheet(sheet.name)
        for(row <- sheet.rows) {
          val eRow = eSheet.createRow(row.index)
          for (cell <- row.cells) {
            eRow.createCell(cell.index, org.apache.poi.ss.usermodel.Cell.CELL_TYPE_STRING).setCellValue(cell.value)
          }
        }
      }
      workbook.write(out)
    }
  }


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
