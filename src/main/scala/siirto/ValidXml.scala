package siirto

import java.io.{Reader, _}
import java.net.URL
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import javax.xml.transform.Source
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.sax.SAXSource
import javax.xml.validation.SchemaFactory

import org.w3c.dom.ls.{LSInput, LSResourceResolver}
import org.xml.sax.{ErrorHandler, SAXParseException}

import scala.collection.mutable
import scala.xml.Source._
import scala.xml._
import scala.xml.parsing.{FactoryAdapter, NoBindingFactoryAdapter}
import scalaz._, Scalaz._


import fi.vm.sade.hakurekisteri.tools.XmlHelpers._


trait XMLValidator[T <: Validation[E, R], E, R <: scala.xml.Node] {
  import scala.xml.Source._
  def adapter: FactoryAdapter

  /* Override this to use a different SAXParser. */
  def parser: SAXParser

  /**
   * Loads XML from the given InputSource, using the supplied parser.
   *  The methods available in scala.xml.XML use the XML parser in the JDK.
   */
  def loadXML(source: InputSource, parser: SAXParser): T

  /** Loads XML from the given file, file descriptor, or filename. */
  def loadFile(file: File): T = loadXML(fromFile(file), parser)
  def loadFile(fd: FileDescriptor): T = loadXML(fromFile(fd), parser)
  def loadFile(name: String): T = loadXML(fromFile(name), parser)

  /** loads XML from given InputStream, Reader, sysID, InputSource, or URL. */
  def load(is: InputStream): T = loadXML(fromInputStream(is), parser)
  def load(reader: Reader): T = loadXML(fromReader(reader), parser)
  def load(sysID: String): T = loadXML(fromSysId(sysID), parser)
  def load(source: InputSource): T = loadXML(source, parser)
  def load(url: URL): T = loadXML(fromInputStream(url.openStream()), parser)

  /** Loads XML from the given String. */
  def loadString(string: String): T = loadXML(fromString(string), parser)
}

class ValidXml(schemaDoc: SchemaDefinition, imports: SchemaDefinition*) extends XMLValidator[ValidationNel[(String, SAXParseException), Elem],NonEmptyList[(String, SAXParseException)], Elem] {

  lazy val resolver = new LSResourceResolver {

    val localSchemaSources: List[SchemaDefinition] = List(Perustiedot) ++ imports


    lazy val schemaCache ={
      val given = localSchemaSources.map(_.schemaLocation).toSet
      localSchemaSources.collect{
        case wr: SchemaWithRemotes => wr +: wr.remotes.filterNot((sd) => given.contains(sd.schemaLocation))
        case s => Seq(s)
      }.flatten.map((sd) => sd.schemaLocation -> sd.schema).toMap

    }

    override def resolveResource(tyyppi: String, namespaceURI: String, publicId: String, systemId: String, baseURI: String): LSInput = {

      def schemaUrl(systemId:String) = systemId match
      {
        case localFile if Option(getClass.getResource(localFile)).isDefined =>  getClass.getResource(localFile).getPath
        case url if url.startsWith("http") => systemId

      }

      return new LSInput {
        override def setCertifiedText(certifiedText: Boolean): Unit = ???

        override def getCertifiedText: Boolean = ???

        override def setEncoding(encoding: String): Unit = ???

        override def getEncoding: String = null

        override def setBaseURI(baseURI: String): Unit = ???

        override def getBaseURI: String = null

        override def setPublicId(publicId: String): Unit = ???

        override def getPublicId: String = publicId

        override def setSystemId(systemId: String): Unit = ???

        override def getSystemId: String = systemId

        override def setStringData(stringData: String): Unit = ???

        override def getStringData: String = schemaCache.getOrElse(systemId,loadRemote(systemId)).toString

        override def setByteStream(byteStream: InputStream): Unit = ???

        override def getByteStream: InputStream = null

        override def setCharacterStream(characterStream: Reader): Unit = ???

        override def getCharacterStream: Reader = null

        def loadRemote(systemId: String): Elem = {
          XML.load(schemaUrl(systemId))
        }
      }



    }


  }



  lazy val schemaFactory=  {
    val factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
    factory.setResourceResolver(resolver)
    factory
  }

  lazy val schema = {
    schemaFactory.newSchema(new SAXSource(fromReader(new StringReader(schemaDoc.schema.toString))))
  }

  def validate(xml: Elem):ValidationNel[(String, SAXParseException), Elem] = validate(new SAXSource(fromString(xml.toString))).map((_) => xml)

  def validate(source: Source): ValidationNel[(String, SAXParseException), Elem] = {
    val exceptions = new mutable.Stack[(String, SAXParseException)]

    val handler = new ErrorHandler{
      override def fatalError(e: SAXParseException): Unit = {exceptions.push("fatal" -> e)}

      override def error(e: SAXParseException): Unit = {exceptions.push("error" -> e)}

      override def warning(e: SAXParseException): Unit = {exceptions.push("warn" -> e)}
    }
    val validator  = schema.newValidator()
    validator.setErrorHandler(handler)
    //validator.validate(new SAXSource(fromString(xml.toString)))
    validator.validate(source)


    exceptions.toList.toNel.map(_.fail).getOrElse(<result/>.successNel)
  }

  override def parser = {
    val f: SAXParserFactory = SAXParserFactory.newInstance()
    f.setNamespaceAware(true)
    f.setValidating(false)
    f.setSchema(schema)
    f.newSAXParser()
  }


  override def loadXML(source: InputSource, parser: SAXParser): ValidationNel[(String, SAXParseException), Elem] = {
    val newAdapter = adapter
    newAdapter.scopeStack push TopScope
    parser.parse(source, newAdapter)
    newAdapter.scopeStack.pop()
    newAdapter.validatedResult(_.rootElem.asInstanceOf[Elem])
  }



  trait ErrorCollector { this: FactoryAdapter =>


    def validatedResult[R](f:FactoryAdapter => R) : ValidationNel[(String, SAXParseException), R]

  }

  case class CollectedException(exceptions: List[(String, SAXParseException)]) extends IOException("Multiple exceptions occured")

  override def adapter: FactoryAdapter with ErrorCollector = new NoBindingFactoryAdapter() with ErrorCollector {

    val exceptions = new mutable.Stack[(String, SAXParseException)]

    import scalaz._, Scalaz._


    def validatedResult[R](f:FactoryAdapter => R): ValidationNel[(String, SAXParseException), R] = {
      exceptions.toList.toNel.map(_.fail).getOrElse(f(this).successNel)
    }

    override def fatalError(e: SAXParseException): Unit = {exceptions.push("fatal" -> e)}

    override def error(e: SAXParseException): Unit = {exceptions.push("error" -> e)}

    override def warning(e: SAXParseException): Unit = {exceptions.push("warn" -> e)}


  }


}





