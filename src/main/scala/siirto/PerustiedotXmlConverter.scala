package siirto

import fi.vm.sade.hakurekisteri.rest.support.XmlConverter
import org.scalatra.servlet.FileItem

import scala.xml.Elem

object PerustiedotXmlConverter extends XmlConverter {
  override def convert(f: FileItem): Elem = ???
}
