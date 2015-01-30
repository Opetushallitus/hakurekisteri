package fi.vm.sade.hakurekisteri.tools


import com.sun.org.apache.xml.internal.serialize.{XMLSerializer, OutputFormat}

import scala.language.implicitConversions
import scala.xml._


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

    def printDom(doc: org.w3c.dom.Document) = {
      val format = new OutputFormat(doc)
      format.setIndenting(true)
      val serializer = new XMLSerializer(System.out, format)
      serializer.serialize(doc)
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
}
