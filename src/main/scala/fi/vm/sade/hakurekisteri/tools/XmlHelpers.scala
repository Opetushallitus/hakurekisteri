package fi.vm.sade.hakurekisteri.tools


import java.io.{InputStream, Reader}
import java.net.URL

import javax.xml.parsers.SAXParserFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.{OutputKeys, TransformerFactory}

import scala.language.implicitConversions
import scala.xml._

// Protect from XXE
// https://www.owasp.org/index.php/XML_External_Entity_(XXE)_Processing
// https://www.owasp.org/index.php/XML_External_Entity_(XXE)_Prevention_Cheat_Sheet
object SafeXML {

  def safeXML = {
    val spf: SAXParserFactory = safeSAXParserFactory
    XML.withSAXParser(spf.newSAXParser())
  }

  def safeSAXParserFactory: SAXParserFactory = {
    val spf = SAXParserFactory.newInstance()
    spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    spf.setFeature("http://xml.org/sax/features/external-general-entities", false)
    spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    spf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    spf.setXIncludeAware(false)
    spf
  }

  def loadString(s: String) = {
    safeXML.loadString(s)
  }

  def load(sysID: String) = {
    safeXML.load(sysID)
  }

  def load(is: InputStream) = {
    safeXML.load(is)
  }

  def load(reader: Reader) = {
    safeXML.load(reader)
  }

  def load(url: URL) = {
    safeXML.load(url)
  }
}

object XmlHelpers {
  def docBuilder =
    javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()

  implicit def nodeExtras(n: scala.xml.Node): NodeExtras = new NodeExtras(n)
  implicit def elemExtras(e: Elem): ElemExtras = new ElemExtras(e)

  class NodeExtras(n: scala.xml.Node) {
    def toJdkNode(doc: org.w3c.dom.Document): org.w3c.dom.Node =
      n match {
        case Elem(prefix, label, attributes, scope, children @ _*) =>
          // XXX: ns
          val r = doc.createElement(label)
          for (a <- attributes) {
            r.setAttribute(a.key, a.value.text)
          }
          for (c <- children) {
            r.appendChild(c.toJdkNode(doc))
          }
          r
        case Text(text) => doc.createTextNode(text)
        case Comment(comment) => doc.createComment(comment)
        // not sure
        case a: Atom[_] => doc.createTextNode(a.data.toString)
        // XXX: other types
        //case x => throw new Exception(x.getClass.getName)
      }
  }

  class ElemExtras(e: Elem) extends NodeExtras(e) {
    override def toJdkNode(doc: org.w3c.dom.Document) =
      super.toJdkNode(doc).asInstanceOf[org.w3c.dom.Element]

    def toJdkDoc = {
      val doc = XmlHelpers.docBuilder.newDocument()
      doc.appendChild(toJdkNode(doc))
      doc
    }
  }

  implicit def dom2Extras(n: org.w3c.dom.Node): DomExtras = new DomExtras(n)

  class DomExtras(n: org.w3c.dom.Node) {
    def toScala: Node = n match {
      case d: org.w3c.dom.Document => d.getDocumentElement.toScala
      case e: org.w3c.dom.Element => convertElement(e)
      case t: org.w3c.dom.Text => Text(t.getWholeText)
    }

    def printDom(doc: org.w3c.dom.Document): Unit = {
      val tform = TransformerFactory.newInstance.newTransformer
      tform.setOutputProperty(OutputKeys.INDENT, "yes")
      tform.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
      tform.transform(new DOMSource(doc), new StreamResult(System.out))
    }

    def convertElement(e: org.w3c.dom.Element): Elem = {
      var attributes: MetaData = Null
      val attribs = e.getAttributes
      val dchildren = e.getChildNodes
      for (
        i <- 0 until attribs.getLength
      ) Option(attribs.item(i)).collect { case a: org.w3c.dom.Attr => Attribute(None, a.getName, Text(a.getValue), Null)}.foreach((a) => attributes = attributes.copy(a))
      val children = for (
        i <- 0 until dchildren.getLength
      ) yield dchildren.item(i).toScala

      Elem(null, e.getTagName, attributes, TopScope, minimizeEmpty = true, children:_*)
    }
  }

  def wrapIntoElement(label: String, content: Any): Elem = {
    <x>{content}</x>.copy(label = label)
  }
}
