package siirto

import fi.vm.sade.hakurekisteri.rest.support.{Workbook}

import DataCollectionConversions._
import ExcelConversions._

import scala.xml.{Node, Elem}
import scalaz._
import fi.vm.sade.hakurekisteri.web.rest.support

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
