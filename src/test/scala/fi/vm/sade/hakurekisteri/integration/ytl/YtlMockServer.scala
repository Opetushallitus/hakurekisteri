package fi.vm.sade.hakurekisteri.integration.ytl

import java.net.SocketException
import java.util.UUID

import javax.servlet.http.{HttpServlet, HttpServletRequest, HttpServletResponse}
import fi.vm.sade.hakurekisteri.tools.Zip
import fi.vm.sade.scalaproperties.OphProperties
import fi.vm.sade.utils.tcp.PortChecker
import org.apache.commons.io.IOUtils
import org.eclipse.jetty.security.authentication.BasicAuthenticator
import org.eclipse.jetty.security.{ConstraintMapping, ConstraintSecurityHandler, HashLoginService}
import org.eclipse.jetty.server.{RequestLog, Server}
import org.eclipse.jetty.servlet.{ServletContextHandler, ServletHolder}
import org.eclipse.jetty.util.security.{Constraint, Credential}
import org.scalatest.{Outcome, TestSuite, TestSuiteMixin, fixture}

import scala.collection.mutable

trait YtlMockFixture extends TestSuiteMixin {
  this: TestSuite =>
  private val ytlMockServer = new YtlMockServer

  def statusUrl = "http://localhost:" + ytlMockServer.port + "/api/oph-transfer/status/$1"
  def bulkUrl = "http://localhost:" + ytlMockServer.port + "/api/oph-transfer/bulk"
  def downloadUrl = "http://localhost:" + ytlMockServer.port + "/api/oph-transfer/bulk/$1"
  def fetchOneUrl = "http://localhost:" + ytlMockServer.port + "/api/oph-transfer/student/$1"
  def username = ytlMockServer.username
  def password = ytlMockServer.password
  def ytlProperties = new OphProperties()
    .addDefault("ytl.http.host.bulk", bulkUrl)
    .addDefault("ytl.http.host.download", downloadUrl)
    .addDefault("ytl.http.host.fetchone", fetchOneUrl)
    .addDefault("ytl.http.host.status", statusUrl)
    .addDefault("ytl.http.username", username)
    .addDefault("ytl.http.password", password)
  ytlMockServer.start()

  abstract override def withFixture(test: NoArgTest): Outcome = {
    var outcome: Outcome = null
    val statementBody = () => outcome = super.withFixture(test)
    statementBody()
    outcome
  }

  def makePostFail(times: Int): Unit = {
    ytlMockServer.makePostFail(times)
  }
}

class YtlMockServlet extends HttpServlet {
  val fakeProsesses: mutable.Map[String, Integer] = mutable.Map()
  private var postFailureCounter: Int = 0

  override protected def doPost(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
    if (postFailureCounter > 0) {
      postFailureCounter = postFailureCounter - 1
      throw new SocketException(
        s"Itse aiheutettu virhe, vielä $postFailureCounter virheellistä vastausta tulossa"
      )
    }
    val fetchBulk = "/api/oph-transfer/bulk"
    val uri = req.getRequestURI
    consumeBodySoThatClientCanWriteEverythingItWants(req)
    uri match {
      case x if x == fetchBulk =>
        val uuid = UUID.randomUUID().toString
        fakeProsesses.put(uuid, 0)
        val json = s"""{"operationUuid": "$uuid"}"""
        resp.getWriter().print(s"$json\n")
      case _ => resp.sendError(500)
    }
  }
  override protected def doGet(req: HttpServletRequest, resp: HttpServletResponse) {
    val uri = req.getRequestURI
    val fetchOne = "/api/oph-transfer/student/(.*)".r
    val pollBulk = "/api/oph-transfer/status/(.*)".r
    val downloadBulk = "/api/oph-transfer/bulk/(.*)".r

    uri match {
      case fetchOne(hetu) =>
        val json = scala.io.Source
          .fromFile(getClass.getResource("/ytl-student.json").getFile)
          .getLines
          .mkString
        resp.getWriter().print(s"$json\n")
      case pollBulk(uuid) =>
        val json = """{
        "created": "2016-09-05T12:44:12.707557+03:00",
        "name": "oph-transfer-generation",
        "finished": "2016-09-05T12:44:12.844439+03:00",
        "failure": null,
        "status": null}""";
        resp.getWriter().print(s"$json\n")
      case downloadBulk(uuid) => {

        val source = Zip.zipit(getClass.getResource("/s.json").openStream())
        val writer = resp.getOutputStream
        IOUtils.copy(source, writer)
        IOUtils.closeQuietly(source)
        IOUtils.closeQuietly(writer)
      }
      case _ => {
        resp.sendError(500)
      }
    }
  }

  private def consumeBodySoThatClientCanWriteEverythingItWants(req: HttpServletRequest) = {
    IOUtils.toString(req.getInputStream, "UTF-8")
  }

  def makePostFail(times: Int): Unit = {
    postFailureCounter = times
  }
}

class YtlMockServer {

  def freePort() = PortChecker.findFreeLocalPort
  val port = freePort()
  val username = "ytluser"
  val password = "ytlpassword"

  val server = new Server(port);

  private var servlet: YtlMockServlet = _

  def start(): Unit = {
    val context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setSecurityHandler(basicAuth(username, password, "Private!"));
    context.setContextPath("/");
    val v: RequestLog = new RequestLog {
      def log(var1: org.eclipse.jetty.server.Request, var2: org.eclipse.jetty.server.Response) = {
        // logging every request to server goes here!
      }
    }
    server.setRequestLog(v)
    server.setHandler(context);
    servlet = new YtlMockServlet()
    context.addServlet(new ServletHolder(servlet), "/*");
    server.start();
    while (!server.isRunning) {
      println("Waiting")
      Thread.sleep(50L)
    }
  }

  def stop() = server.stop()

  def basicAuth(username: String, password: String, realm: String): ConstraintSecurityHandler = {
    val l = new HashLoginService();
    l.putUser(username, Credential.getCredential(password), Array[String] { "user" });
    //l.setName(realm);

    val constraint = new Constraint();
    constraint.setName(Constraint.__BASIC_AUTH);
    constraint.setRoles(Array[String] { "user" });
    constraint.setAuthenticate(true);

    val cm = new ConstraintMapping();
    cm.setConstraint(constraint);
    cm.setPathSpec("/*");

    val csh = new ConstraintSecurityHandler();
    csh.setAuthenticator(new BasicAuthenticator());
    csh.setRealmName("myrealm");
    csh.addConstraintMapping(cm);
    csh.setLoginService(l);

    return csh;
  }

  def makePostFail(times: Int): Unit = {
    servlet.makePostFail(times)
  }
}
