package fi.vm.sade.hakurekisteri.hakija

import info.folone.scala.poi._
import scalaz._
import syntax.monoid._
import syntax.foldable._
import std.list._
import java.io.OutputStream
import scalaz.effect.IO

object ExcelUtil {

  def getHeaders(): Set[Row] = {
    Set(
      Row(1) { // headers
        Set(StringCell(1, "HETU"))
      }
    )
  }

  def getRows(hakijat: XMLHakijat): Set[Row] = {
    hakijat.hakijat.zipWithIndex.map((t) => {
      Row(t._2 + 2) {
        Set(StringCell(1, t._1.hetu))
      }
    }).toSet
  }

  def writeHakijatAsExcel(hakijat: XMLHakijat, out: OutputStream) {
    val sheet = new Sheet("Hakijat")(getHeaders ++ getRows(hakijat))

    val wb = new Workbook(Set(sheet))
    wb.safeToStream(out)
  }

}
