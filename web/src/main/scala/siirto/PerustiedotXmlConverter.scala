package siirto

import fi.vm.sade.hakurekisteri.rest.support.{LocalDateSerializer, Workbook}
import org.joda.time.format.{ISODateTimeFormat, DateTimeFormat}

import org.scalatra.servlet.FileItem
import DataCollectionConversions._
import ExcelConversions._

import scala.xml.{Node, Elem}
import scalaz._
import fi.vm.sade.hakurekisteri.web.rest.support
import org.apache.poi.ss.usermodel.WorkbookFactory

object PerustiedotXmlConverter extends support.XmlConverter with ExcelToXmlSupport {
  def convert(workbook: Workbook, filename: String): Elem = {
    val xml = converter.set(<henkilot/>, workbook)
    <perustiedot>
      <eranTunniste>{filename}</eranTunniste>
      {xml}
    </perustiedot>
  }

  def itemIdentity(item: Elem): Elem = {
    item.copy(child = (item \ "hetu") ++ (item \ "oppijanumero") ++ (item \ "henkilotunniste"))
  }

  def addIdentity(row: DataRow, nodes: Seq[Node]): Seq[Node] = {
    val id = row.collectFirst{
      case DataCell("HETU", i) if i != "" => ("hetu", i)
      case DataCell("OPPIJANUMERO", i) if i != "" => ("oppijanumero", i)
      case DataCell("HENKILOTUNNISTE", i) if i != "" => ("henkiloTunniste", i)
    }.get
    nodes match {
      case n if !n.exists((idNode) => idNode.label == "hetu" || idNode.label == "oppijanumero" || idNode.label == "henkiloTunniste") => nodes ++ <id>{id._2}</id>.copy(label = id._1)
      case default => default
    }
  }

  def henkiloLens: Elem @> DataRow = Lens.lensu(
    (item, row) =>
    {
      val sheetData =
      {row.collect {
        case DataCell("SYNTYMAAIKA", v) if v != "" => <syntymaAika>{toXmlDate(v)}</syntymaAika>
        case DataCell(name, v) if v != "" && Set("SUKUPUOLI", "LAHTOKOULU", "LUOKKA", "SUKUNIMI", "ETUNIMET", "KUTSUMANIMI", "KOTIKUNTA", "AIDINKIELI", "KANSALAISUUS", "LAHIOSOITE", "POSTINUMERO", "MAA", "MATKAPUHELIN").contains(name) =>
          <tag>{v}</tag>.copy(label = name.toLowerCase)
        case DataCell("MUUPUHELIN", v) if v != "" => <muuPuhelin>{v}</muuPuhelin>
      }}

      item.copy(child = addIdentity(row, item.child) ++ sheetData)
    }
    ,
    (item) => Seq()
  )

  def yksilollistettavaLens(elementName: String): Elem @> DataRow = Lens.lensu(
    (item, row) => {
      val sheetData = <s>
        {row.collect {
          case DataCell("VALMISTUMINEN", v) => <valmistuminen>{toXmlDate(v)}</valmistuminen>
          case DataCell(name, v) if v != "" && Set("MYONTAJA", "SUORITUSKIELI", "TILA", "YKSILOLLISTAMINEN").contains(name) =>
            <tag>{v}</tag>.copy(label = name.toLowerCase)
        }}
      </s>.copy(label = elementName)
      val result = item.copy(child = addIdentity(row, item.child) ++ sheetData)
      result
    },
    (item) => Seq()
  )

  def suoritusLens(elementName: String): Elem @> DataRow = Lens.lensu(
    (item, row) => {
      val sheetData = <s>
        {row.collect {
          case DataCell("VALMISTUMINEN", v) => <valmistuminen>{toXmlDate(v)}</valmistuminen>
          case DataCell(name, v) if v != "" && Set("MYONTAJA", "SUORITUSKIELI", "TILA").contains(name) =>
            <tag>{v}</tag>.copy(label = name.toLowerCase)
        }}
      </s>.copy(label = elementName)
      val result = item.copy(child = addIdentity(row, item.child) ++ sheetData)
      result
    },
    (item) => Seq()
  )

  val converter: WorkBookExtractor = ExcelExtractor(itemIdentity _, <henkilo/>)(
    "henkilotiedot" -> henkiloLens,
    "perusopetus" -> yksilollistettavaLens("perusopetus"),
    "perusopetuksenlisaopetus" -> yksilollistettavaLens("perusopetuksenlisaopetus"),
    "ammattistartti" -> suoritusLens("ammattistartti"),
    "valmentava" -> suoritusLens("valmentava"),
    "maahanmuuttajienlukioonvalmistava" -> suoritusLens("maahanmuuttajienlukioonvalmistava"),
    "maahanmuuttajienammvalmistava" -> suoritusLens("maahanmuuttajienammvalmistava"),
    "ulkomainen" -> suoritusLens("ulkomainen"),
    "lukio" -> suoritusLens("lukio"),
    "ammatillinen" -> suoritusLens("ammatillinen")
  )
}

trait ExcelToXmlSupport {
  private val excelContentTypes = Set("application/vnd.ms-excel", "application/msexcel", "application/x-msexcel", "application/x-ms-excel", "application/x-excel", "application/x-dos_ms_excel", "application/xls", "application/x-xls", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
  private val XmlDate = "[0-9]{4}-[0-9]{2}-[0-9]{2}".r

  def isExcel(f: FileItem): Boolean = f.name.toLowerCase.endsWith(".xls") || f.name.toLowerCase.endsWith(".xlsx") || f.getContentType.exists(excelContentTypes.contains)

  def toXmlDate(value: String): String = value match {
    case XmlDate() => value
    case finDate => ISODateTimeFormat.yearMonthDay().print(DateTimeFormat.forPattern(LocalDateSerializer.dayFormat).parseDateTime(finDate))
  }
}
