package fi.vm.sade.hakurekisteri.integration.ytl

import javax.servlet.http.{HttpServletResponse, HttpServletRequest, HttpServlet}

import org.eclipse.jetty.security.authentication.BasicAuthenticator
import org.eclipse.jetty.security.{ConstraintSecurityHandler, ConstraintMapping, HashLoginService}
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.{ServletHolder, ServletContextHandler}
import org.eclipse.jetty.util.security.{Constraint, Credential}
import org.scalatest.{Outcome, Suite, SuiteMixin}

trait YtlMockFixture extends SuiteMixin {
  this: Suite =>
  private val ytlMockServer = new YtlMockServer

  def url = s"http://localhost:${ytlMockServer.port}"
  def username = ytlMockServer.username
  def password = ytlMockServer.password

  abstract override def withFixture(test: NoArgTest) = {
    var outcome: Outcome = null
    val statementBody = () => outcome = super.withFixture(test)
    ytlMockServer.start()
    statementBody()
    ytlMockServer.stop()
    /*
    temporaryFolder(
      new Statement() {
        override def evaluate(): Unit = statementBody()
      },
      Description.createSuiteDescription("JUnit rule wrapper")
    ).evaluate()
    */
    outcome
  }
}


class YtlMockServlet extends HttpServlet {

  override protected def doGet(req: HttpServletRequest, resp: HttpServletResponse) {
    val path = req.getContextPath
    val json = scala.io.Source.fromFile(getClass.getResource("/ytl-student.json").getFile).getLines.mkString

    resp.getWriter().print(s"$json\n");
  }
}


class YtlMockServer {

  def freePort() = {
    5000
  }
  val port = freePort()
  val username = "ytluser"
  val password = "ytlpassword"


  val server = new Server(port);

  def start(): Unit = {
    val context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setSecurityHandler(basicAuth(username, password, "Private!"));
    context.setContextPath("/");
    server.setHandler(context);
    context.addServlet(new ServletHolder(new YtlMockServlet()),"/*");
    server.start();
    while (!server.isRunning) {
      println("Waiting")
      Thread.sleep(50L)
    }
  }

  def stop() = server.stop()

  def basicAuth(username: String, password: String, realm: String): ConstraintSecurityHandler = {
    val l = new HashLoginService();
    l.putUser(username, Credential.getCredential(password), Array[String]{"user"});
    //l.setName(realm);

    val constraint = new Constraint();
    constraint.setName(Constraint.__BASIC_AUTH);
    constraint.setRoles(Array[String]{"user"});
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

}
