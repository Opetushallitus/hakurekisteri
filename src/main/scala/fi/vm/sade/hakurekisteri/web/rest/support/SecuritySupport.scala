package fi.vm.sade.hakurekisteri.web.rest.support

import java.net.InetAddress
import java.security.Principal

import javax.servlet.http.HttpServletRequest
import fi.vm.sade.hakurekisteri.{Config, AuditUtil}
import fi.vm.sade.hakurekisteri.rest.support.{AuditSessionRequest, OPHUser, User}
import fi.vm.sade.javautils.http.HttpServletRequestUtils
import javax.servlet.http.HttpServletRequest
import org.apache.commons.lang3.builder.ToStringBuilder
import org.jasig.cas.client.authentication.AttributePrincipalImpl
import org.jasig.cas.client.validation.AssertionImpl
import org.springframework.security.cas.authentication.CasAuthenticationToken
import org.springframework.security.core.{Authentication, GrantedAuthority}

import scala.collection.JavaConverters._

trait SecuritySupport {
  implicit val security: Security
  def currentUser(implicit request: HttpServletRequest): Option[User] = security.currentUser
  def auditUser(implicit requst: HttpServletRequest): fi.vm.sade.auditlog.User = security.auditUser
}

object Security {
  def apply(config: Config): Security = if (config.mockMode) {
    new TestSecurity
  } else {
    new SpringSecurity
  }
}

trait Security {
  def currentUser(implicit request: HttpServletRequest): Option[User]
  def auditUser(implicit request: HttpServletRequest): fi.vm.sade.auditlog.User
  def security: Security = this
}

class SpringSecurity extends Security {
  import scala.collection.JavaConverters._

  private def userAgent(r: HttpServletRequest): String = Option(r.getHeader("User-Agent")).getOrElse("Unknown user agent")
  private def inetAddress(r: HttpServletRequest): String = HttpServletRequestUtils.getRemoteAddress(r)

  override def currentUser(implicit request: HttpServletRequest): Option[User] = userPrincipal.map { a =>
    OPHUser(username(a), authorities(a).toSet,userAgent(request),inetAddress(request), a)
  }

  override def auditUser(implicit request: HttpServletRequest): fi.vm.sade.auditlog.User = {
    AuditUtil.parseUser(request, currentUser.get.username)
  }

  def username(u: Principal): String = {
    Option(u.getName).getOrElse("anonymous")
  }

  def authorities(auth: Authentication): Iterable[String] = for (
    authority <- granted(auth)
    if Option(authority.getAuthority).isDefined
  ) yield authority.getAuthority

  def granted(auth: Authentication): Iterable[GrantedAuthority] = for (
    authority <- auth.getAuthorities.asScala
    if Option(authority).isDefined
  ) yield authority


  def userPrincipal(implicit request: HttpServletRequest): Option[CasAuthenticationToken] = Option(request.getUserPrincipal)
    .map(_.asInstanceOf[CasAuthenticationToken])
}

class TestSecurity extends Security {
  override def currentUser(implicit request: HttpServletRequest): Option[fi.vm.sade.hakurekisteri.rest.support.User] = Some(TestUser)
  override def auditUser(implicit request: HttpServletRequest): fi.vm.sade.auditlog.User = new fi.vm.sade.auditlog.User(InetAddress.getByName("111.222.111.2"), "abc999_test", "mockAgent")

}

object TestUser extends User {
  override def orgsFor(action: String, resource: String): Set[String] = Set("1.2.246.562.10.00000000001")
  override val username: String = "Test"
  override val auditSession = AuditSessionRequest(username, Set("1.2.246.562.10.00000000001"), "", "")
  override def toString: String = ToStringBuilder.reflectionToString(this)
  override def casAuthenticationToken: CasAuthenticationToken = new CasAuthenticationToken("key", "principal", "credentials", Nil.asJava, null, new AssertionImpl(new AttributePrincipalImpl("testprincipal")))
}
