package siirto

import scala.xml.Elem

import scala.language.implicitConversions
import scalaz._
import scala.collection.immutable.IndexedSeq
import org.apache.poi.ss.usermodel
import fi.vm.sade.hakurekisteri.rest.support.{Workbook, Cell, Row, Sheet}
import siirto.DataCollectionConversions._
import scala.util.matching.Regex


object DataCollectionConversions {


  case class DataCell(name: String, value: String)

  type CellExtractor = Lens[DataCell, Elem]

  val defaultCellExtractor: CellExtractor = Lens.lensu(
    (cell, elem) => DataCell(elem.label, elem.text),
    (cell) => <element>{cell.value}</element>.copy(label = cell.name)
  )

  type DataRow = Seq[DataCell]

  type RowExtractor = LensFamily[Elem, Elem, DataCollection, DataRow]


  type DataCollection = Seq[DataRow]



  type XmlConverter = Elem @> DataCollection

  type RowWriter = (Elem, DataRow) => Elem

  type CollectionReader = Elem => DataCollection


  object XmlConverter {
    def apply(rc: RowExtractor): XmlConverter = Lens.lensu((current: Elem, coll: DataCollection) =>  coll.foldLeft(current)(rc.set), rc.get)

    def apply(w: RowWriter, r: CollectionReader): XmlConverter = apply(LensFamily.lensFamilyu(w,r))

  }



}

object ExcelConversions {

  type ExcelSheetExtractor = Elem @> Sheet

  object ExcelSheetExtractor {


    def readSheet (sheet: Sheet):DataCollection = {
      val rows = sheet.rows.toList.sortBy(_.index)
      rows match {
        case headers :: data if data.length > 0  =>
          val cellNames = headers.cells.map((cell) => cell.index -> cell.value).toMap
          data.map(_.cells.toSeq.map((cell) => DataCell(cellNames(cell.index), cell.value)))
        case _ => Seq()
      }
    }

    def writeSheet(name: String)(dc: DataCollection): Sheet = {

      val columns: Map[String, Int] = dc.foldLeft[Set[String]](Set[String]())((cur, row) => cur ++ row.map(_.name)).toSeq.zipWithIndex.toMap


      Sheet(name,
        dc.zipWithIndex.map{ case (datarow, index) => Row(index + 1)(datarow.map((dc) => Cell(columns(dc.name), dc.value)):_*) }.toSet +
          Row(0)(columns.map{ case (value, index) => Cell(index, value)}.toSeq:_*))

    }

    def apply(name: String, xc: XmlConverter): ExcelSheetExtractor = xc.xmapB[Sheet, Sheet](writeSheet(name))(readSheet)


    def  apply(name: String, w: RowWriter, r: CollectionReader): ExcelSheetExtractor = apply(name, XmlConverter(w, r))

  }

  type WorkBookExtractor = Elem @> Workbook



  object ExcelExtractor {

    type DataElem = Elem
    type ItemElem = Elem
    type IdElem = Elem

    def apply(converters: (String, (RowWriter, CollectionReader))*): WorkBookExtractor = {
      val sheetConverters = converters.map{ case (sheetName, (writer, reader)) => sheetName -> ExcelSheetExtractor(sheetName, writer, reader)}.toMap
      Lens.lensu(
        (elem, wb) => {
          wb.sheets.collect{
            case s:Sheet if sheetConverters.isDefinedAt(s.name) => s -> sheetConverters(s.name)
          }.foldLeft(elem){case (current, (sheet, converter)) => converter.set(current, sheet)}
        },
        (elem) => new Workbook((for (
          (sheetName, (writer, reader)) <- converters
        ) yield ExcelSheetExtractor(sheetName, writer, reader).get(elem)).toSet)
      )


    }

    def apply(itemIdentity: ItemElem => IdElem, itemTag: Elem)(lenses: (String, ItemElem @> DataRow)*):WorkBookExtractor = {
      apply(lenses.map{case (sheet, lens) => sheet -> RowHandler(itemIdentity, itemTag, lens)}:_*)
    }

    object RowHandler {



      import scalaz._, Scalaz._

      def itemSetter(identifier: ItemElem => scala.xml.Node => Boolean)(data: DataElem)(item:ItemElem): DataElem = {
        val span = data.child.span((node) => !identifier(item)(node))
        val newItems = span match {
          case (before, empty) if empty.length == 0 =>
            before ++ item
          case (before, after)  =>
            before ++ (item +: after.tail)
        }
        data.copy(child = newItems)
      }


      def lensForRow(identifier: (ItemElem) => IdElem,itemTag: Elem,  itemLens: ItemElem @> DataRow, row: DataRow): LensFamily[ExcelExtractor.DataElem, ExcelExtractor.DataElem, Option[ItemElem], ExcelExtractor.ItemElem] = {
        val identifyItem = createIdentifier(identifier) _
        LensFamily.lensFamilyg(
          itemSetter(identifyItem),
          (data: DataElem) => {
            val result = data.child.collectFirst {
              case e: scala.xml.Elem if identifyItem(itemLens.set(itemTag, row))(e) => e
            }
            result
          }

        )
      }

      def writer(identifier: (ItemElem) => IdElem, itemTag: Elem, itemLens: ItemElem @> DataRow):RowWriter = (elem: DataElem, row: DataRow) => {
        val function = (item: Option[ItemElem]) => {
          itemLens.set(item.getOrElse(itemTag), row)
        }
        lensForRow(identifier, itemTag, itemLens, row).mod(function, elem)
      }

      def reader(itemLens: ItemElem @> DataRow): DataElem => DataCollection = _.child.collect{case e: Elem => e}.map(itemLens.get)

      def createIdentifier(identifier: (ItemElem) => IdElem)(item: Elem)(tested: scala.xml.Node): Boolean = tested match {
        case e: Elem if e.label == item.label && identifier(e) == identifier(item) =>
          true
        case default => false
      }

      def apply(itemIdentity: ItemElem => IdElem, itemTag: Elem, itemLens: ItemElem @> DataRow): (RowWriter, CollectionReader) = (writer(itemIdentity, itemTag, itemLens), reader(itemLens))
    }






  }


}