package siirto

import fi.vm.sade.hakurekisteri.rest.support.Workbook

import org.scalatra.servlet.FileItem
import DataCollectionConversions._
import ExcelConversions._

import scala.xml.{Node, Elem}
import scalaz._
import fi.vm.sade.hakurekisteri.web.rest.support
import org.apache.poi.ss.usermodel.WorkbookFactory


object ArvosanatXmlConverter extends support.XmlConverter with ExcelToXmlSupport {

  override def convert(f: FileItem): Elem = f match {
    case excelFile if isExcel(excelFile) =>
      val xml = converter.set(<henkilot/>, Workbook(WorkbookFactory.create(f.getInputStream)))
      <arvosanat>
        <eranTunniste>{excelFile.getName}</eranTunniste>
        {xml}
      </arvosanat>
    case file =>
      throw new IllegalArgumentException(s"file ${file.getName} cannot be converted to xml")
  }
  
  def itemIdentity(item: Elem): Elem = {
    item.copy(child = (item \ "hetu") ++ (item \ "oppijanumero") ++ (item \ "henkiloTunniste"))
  }

  def addHenkilotiedot(row: DataRow, henkiloElementContents: Seq[Node]): Seq[Node] = {
    if (henkiloElementContents.isEmpty) {
      convertPersonIdentification(row)
    } else {
      henkiloElementContents
    }
  }

  def convertPersonIdentification(row: DataRow): Seq[Node] = {
    val henkilotiedot: Seq[(String, String)] = row.collect { case DataCell("HETU", i) if i != "" => ("hetu", i)
    case DataCell("OPPIJANUMERO", i) if i != "" => ("oppijanumero", i)
    case DataCell("HENKILOTUNNISTE", i) if i != "" => ("henkiloTunniste", i)
    case DataCell("SYNTYMAAIKA", v) if v != "" => ("syntymaAika", toXmlDate(v))
    case DataCell(name, v) if v != "" && Set("SUKUNIMI", "ETUNIMET", "KUTSUMANIMI").contains(name) => (name.toLowerCase, v)
    }
    henkilotiedot.map{case (label, value) => <tag>{value}</tag>.copy(label = label)} ++ <todistukset/>
  }

  def addTodistus(henkiloElementContents: Seq[Node], todistusElem: Elem): Seq[Node] = {
    henkiloElementContents.map {
      case elem: Elem if (elem.label == "todistukset") => elem.copy(child = elem.child :+ todistusElem)
      case default => default
    }
  }

  def todistusLens(elementName: String): Elem @> DataRow = Lens.lensu(
    (henkiloElem: Elem, row: DataRow) => {
      val todistus = <s>
        {row.collect {
          case DataCell("VALMISTUMINEN", v) => <valmistuminen>{toXmlDate(v)}</valmistuminen>
          case DataCell(name, v) if v != "" && Set("MYONTAJA", "SUORITUSKIELI", "EIVALMISTU").contains(name) =>
            <tag>{v}</tag>.copy(label = name.toLowerCase)
        }}
      </s>.copy(label = elementName)

      val result = henkiloElem.copy(child = addTodistus(addHenkilotiedot(row, henkiloElem.child), todistus))
      result
    },
    (item) => Seq()
  )

  val converter: WorkBookExtractor = ExcelExtractor(itemIdentity _, <henkilo/>)(
    "perusopetus" -> todistusLens("perusopetus")
  )
}
