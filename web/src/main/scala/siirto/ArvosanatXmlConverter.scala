package siirto

import fi.vm.sade.hakurekisteri.rest.support.Workbook

import DataCollectionConversions._
import ExcelConversions._

import scala.xml.{Node, Elem}
import scalaz._
import fi.vm.sade.hakurekisteri.web.rest.support
import fi.vm.sade.hakurekisteri.tools.XmlHelpers.wrapIntoElement

object ArvosanatXmlConverter extends support.XmlConverter with ExcelToXmlSupport {
  def convert(workbook: Workbook, filename: String): Elem = {
    val xml = converter.set(<henkilot/>, workbook)
    <arvosanat>
      <eranTunniste>
        {filename}
      </eranTunniste>{xml}
    </arvosanat>
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
    val henkilotiedot = row.collect { case DataCell("HETU", i) if i != "" => wrapIntoElement("hetu", i)
    case DataCell("OPPIJANUMERO", i) if i != "" => wrapIntoElement("oppijanumero", i)
    case DataCell("HENKILOTUNNISTE", i) if i != "" => wrapIntoElement("henkiloTunniste", i)
    case DataCell("SYNTYMAAIKA", v) if v != "" => wrapIntoElement("syntymaAika", toXmlDate(v))
    case DataCell(name, v) if v != "" && Set("SUKUNIMI", "ETUNIMET", "KUTSUMANIMI").contains(name) => wrapIntoElement(name.toLowerCase, v)
    }
    henkilotiedot ++ <todistukset/>
  }

  def addTodistus(henkiloElementContents: Seq[Node], todistusElem: Elem): Seq[Node] = {
    henkiloElementContents.map {
      case elem: Elem if elem.label == "todistukset" => elem.copy(child = elem.child :+ todistusElem)
      case default => default
    }
  }

  private def todistusLens(elementName: String): Elem @> DataRow = Lens.lensu(
    (henkiloElem: Elem, row: DataRow) => {
      val todistusContents: Seq[Elem] = row.collect {
        case DataCell(name, v) if Set("VALMISTUMINEN", "OLETETTUVALMISTUMINEN", "OPETUSPAATTYNYT").contains(name) =>
          wrapIntoElement(name.toLowerCase, toXmlDate(v))
        case DataCell(name, v) if Set("MYONTAJA", "SUORITUSKIELI", "VALMISTUMINENSIIRTYY").contains(name) =>
          wrapIntoElement(name.toLowerCase, v)
      }

      val eivalmistuElem: Seq[Elem] = row.collect {
        case DataCell(name, v) if name == "EIVALMISTU"=> wrapIntoElement(name.toLowerCase, v)
      }

      val todistus = wrapIntoElement(elementName, todistusContents ++ convertAineet(row) ++ eivalmistuElem)

      val result = henkiloElem.copy(child = addTodistus(addHenkilotiedot(row, henkiloElem.child), todistus))
      result
    },
    (item) => Seq()
  )

  private def convertAineet(row: DataRow): Seq[Elem] = {
    val AineRegex = """(.+)_([A-Z]+).*""".r

    val aineNimetJarjestyksessa = row.collect { case DataCell(AineRegex(aine, _), _) => aine}.distinct

    val aineTiedot = row.collect { case DataCell(AineRegex(aine, lisatieto), v) => (aine, lisatieto, v)}.groupBy(_._1)

    val aineet = aineNimetJarjestyksessa.map(aine => (aine, aineTiedot(aine))).map { case (aine, arvot: Seq[(String, String, String)]) =>
      wrapIntoElement(aine, arvot.map { case (_, lisatieto, v) =>
        val lisatietoElementName = lisatieto.toLowerCase match {
          case "yh" => "yhteinen"
          case "val" => "valinnainen"
          case x => x
        }
        wrapIntoElement(lisatietoElementName, v)
      })
    }
    aineet
  }

  val converter: WorkBookExtractor = ExcelExtractor(itemIdentity _, <henkilo/>)(
    "perusopetus" -> todistusLens("perusopetus"),
    "perusopetuksenlisaopetus" -> todistusLens("perusopetuksenlisaopetus"),
    "ammattistartti" -> todistusLens("ammattistartti"),
    "valmentava" -> todistusLens("valmentava"),
    "maahanmuuttajienlukioonvalmistava" -> todistusLens("maahanmuuttajienlukioonvalmistava"),
    "maahanmuuttajienammvalmistava" -> todistusLens("maahanmuuttajienammvalmistava"),
    "lukio" -> todistusLens("lukio")
  )
}
