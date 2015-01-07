package siirto

import fi.vm.sade.hakurekisteri.rest.support.{LocalDateSerializer, Workbook, XmlConverter}
import org.joda.time.format.{ISODateTimeFormat, DateTimeFormat}
import org.scalatra.servlet.FileItem
import siirto.DataCollectionConversions._
import siirto.ExcelConversions._

import scala.xml.{Node, Elem}
import scalaz._

object PerustiedotXmlConverter extends XmlConverter {
  val excelContentTypes = Set("application/vnd.ms-excel","application/msexcel","application/x-msexcel","application/x-ms-excel","application/x-excel","application/x-dos_ms_excel","application/xls","application/x-xls","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")

  def isExcel(f: FileItem): Boolean = f.name.endsWith(".xls") || f.name.endsWith(".xlsx") || f.getContentType.exists(excelContentTypes.contains)

  override def convert(f: FileItem): Elem = f match {
    case excelFile if isExcel(excelFile) =>
      val xml = converter.set(<henkilot/>, Workbook(excelFile))
      <perustiedot>
        <eranTunniste>{excelFile.getName}</eranTunniste>
        {xml}
      </perustiedot>
    case file =>
      throw new IllegalArgumentException(s"file ${file.getName} cannot be converted to xml")
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

  val henkiloLens: Elem @> DataRow = Lens.lensu(
    (item, row) =>
    {
      val sheetData =
      {row.collect {
        case DataCell("SYNTYMAAIKA", v) if v != "" => <syntymaAika>{ISODateTimeFormat.yearMonthDay().print(DateTimeFormat.forPattern(LocalDateSerializer.dayFormat).parseDateTime(v))}</syntymaAika>
        case DataCell("MUUPUHELIN", v) if v != "" => <muuPuhelin>{v}</muuPuhelin>
        case DataCell(name, v) if v != "" && Set("SUKUPUOLI", "LAHTOKOULU", "LUOKKA", "SUKUNIMI", "ETUNIMET", "KUTSUMANIMI", "KOTIKUNTA", "AIDINKIELI", "KANSALAISUUS", "LAHIOSOITE", "POSTINUMERO", "MAA", "MATKAPUHELIN").contains(name) =>
          <tag>{v}</tag>.copy(label = name.toLowerCase)
      }}

      item.copy(child = addIdentity(row, item.child) ++ sheetData)
    }
    ,
    (item) => Seq()
  )

  val perusopetusLens: Elem @> DataRow = Lens.lensu(
    (item, row) => {
      val sheetData = <perusopetus>
        {row.collect {
          case DataCell("VALMISTUMINEN", v) => <valmistuminen>{ISODateTimeFormat.yearMonthDay().print(DateTimeFormat.forPattern(LocalDateSerializer.dayFormat).parseDateTime(v))}</valmistuminen>
          case DataCell(name, v) if v != "" && Set("MYONTAJA", "SUORITUSKIELI", "TILA", "YKSILOLLISTAMINEN").contains(name) =>
            <tag>{v}</tag>.copy(label = name.toLowerCase)
        }}
      </perusopetus>
      val result = item.copy(child = addIdentity(row, item.child) ++ sheetData)
      result
    },
    (item) => Seq()
  )

  val converter: WorkBookExtractor = ExcelExtractor(itemIdentity _, <henkilo/>)(
    "henkilotiedot" -> henkiloLens,
    "perusopetus" -> perusopetusLens
  )
}
