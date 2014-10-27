import sbt._
import sbt.testing.{OptionalThrowable, TestSelector, Status, Event}
import scala.collection.concurrent.TrieMap
import scala.compat.Platform
import scala.Some
import scala.xml.{Utility, XML, Elem}
import scala.language.implicitConversions

class SurefireListener(targetDir:File) extends TestReportListener {

  val startTimes: scala.collection.concurrent.Map[String,Long] = TrieMap()
  val details: scala.collection.concurrent.Map[String,Seq[Event]] = TrieMap()
  val loggers = TrieMap[String, ContentLogger]()

  val baseDir = new File(targetDir,"surefire-reports")
  baseDir.mkdir()

  class SureFireLogger() extends sbt.testing.Logger {
    val outBuff = new StringBuffer
    val errBuff = new StringBuffer

    def out:String = outBuff.toString
    def err:String = errBuff.toString

    override def trace(t: Throwable): Unit = errBuff.append(t.getStackTrace.map { e => e.toString }.mkString("\n") + "\n")

    override def debug(msg: String): Unit = outBuff.append(msg + "\n")

    override def info(msg: String): Unit = outBuff.append(msg + "\n")

    override def warn(msg: String): Unit = errBuff.append(msg + "\n")

    override def error(msg: String): Unit = errBuff.append(msg + "\n")

    override def ansiCodesSupported(): Boolean = false
  }

  override def contentLogger(test: TestDefinition): Option[ContentLogger] = {val newLogger = new ContentLogger(new SureFireLogger(), () =>{}); Some(loggers.putIfAbsent(test.name, newLogger).getOrElse(newLogger))}

  def propertyTag(keyvalue: (String, String)) = keyvalue match { case (key, value) => <property name={key} value={value}/>}

  def createResults(name:String, result: TestResult.Value, duration:Long) = {
    val time = (duration/1000f).toString
    <testsuite failures={failures(name)} time={time} errors={errors(name)} skipped={skipped(name)} tests={tests(name)} name={name}>
      <properties>{sys.props.map(propertyTag)}</properties>
      {testCases(name)}
      <system-out>{loggers(name).log.asInstanceOf[SureFireLogger].out}</system-out>
      <system-err>{loggers(name).log.asInstanceOf[SureFireLogger].err}</system-err>
    </testsuite>
  }


  def failures(name: String): String = {
    statuses(name).filter(_ eq Status.Failure).length.toString
  }

  def errors(name: String): String = {
    statuses(name).filter(_ eq Status.Error).length.toString
  }

  def skipped(name: String): String = {
    statuses(name).filter(isSkipped).length.toString
  }


  def isSkipped(status: Status): Boolean = {
    Set(Status.Skipped, Status.Pending, Status.Ignored, Status.Canceled).contains(status)
  }

  def tests(name: String): String = {
    statuses(name).length.toString
  }

  def statuses(name: String): Seq[Status] = {
    details(name).map(d => (d.selector(), d.status())).collect {
      case (s: TestSelector, status) => status
    }
  }

  implicit def optionalThrowabletoOptionThrowable(ot: OptionalThrowable): Option[Throwable] = {
    if (ot.isDefined) Some(ot.get()) else None
  }


  def testCases(name: String): Seq[Elem] = {
    details(name).map((detail: Event) => (detail.status,detail.fullyQualifiedName, detail.selector, detail.duration, detail.throwable)).collect {
      case (status, classname, sel: TestSelector, duration, throwable) => testCase(status, sel, classname, duration, throwable)
    }
  }


  def testCase(status:Status, sel: TestSelector, classname: String, duration: Long, cause: Option[Throwable]) = {
    <testcase time={((if (duration < 0) 0 else duration) / 1000f).toString} classname={classname} name ={sel.testName}>
      {
      status match {
        case Status.Failure => <failure message={cause.map(_.getMessage).getOrElse("")} type={cause.map(_.getClass.getName).getOrElse("")}>{cause.map(_.getStackTrace.map { e => e.toString }.mkString("\n")).getOrElse("")}</failure>
        case Status.Error => <error message={cause.map(_.getMessage).getOrElse("")} type={cause.map(_.getClass.getName).getOrElse("")}>{cause.map(_.getStackTrace.map { e => e.toString }.mkString("\n")).getOrElse("")}</error>
        case Status.Success => {}
        case _ => <skipped/>
      }
      }
    </testcase>
  }
  override def endGroup(name: String, result: TestResult.Value):Unit = {
    XML.save(baseDir.getAbsolutePath + "/" + "TEST-" + name + ".xml",Utility.trim(createResults(name, result, (Platform.currentTime - startTimes(name)))),"UTF-8",xmlDecl = true)

  }

  override def endGroup(name: String, t: Throwable): Unit = {}



  override def testEvent(event: TestEvent): Unit = {
    event.detail.headOption.foreach(e => details.put(e.fullyQualifiedName(), event.detail))
  }

  override def startGroup(name: String): Unit = startTimes.put(name, Platform.currentTime)
}
