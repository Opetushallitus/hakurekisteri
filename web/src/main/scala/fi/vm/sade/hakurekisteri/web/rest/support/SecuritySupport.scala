package fi.vm.sade.hakurekisteri.web.rest.support

import java.security.Principal
import javax.servlet.http.HttpServletRequest
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.rest.support.{OPHUser, User}
import org.springframework.security.core.{Authentication, GrantedAuthority}

trait SecuritySupport {
  implicit val security: Security
  def currentUser(implicit request: HttpServletRequest): Option[User] = security.currentUser
}

trait AutomaticSecuritySupport extends SecuritySupport {
  implicit val security = Config.config.mockMode match {
    case true => new TestSecurity
    case false => new SpringSecuritySupport
  }
}

trait Security {
  def currentUser(implicit request: HttpServletRequest): Option[User]
  def security = this
}

class SpringSecuritySupport extends Security {
  import scala.collection.JavaConverters._

  override def currentUser(implicit request: HttpServletRequest): Option[User] = userPrincipal.map {
    case a: Authentication => OPHUser(username(a), authorities(a).toSet)
    case u: Principal => OPHUser(username(u), Set())
  }

  def username(u: Principal): String = {
    Option(u.getName).getOrElse("anonymous")
  }

  def authorities(auth: Authentication) = for (
    authority <- granted(auth)
    if Option(authority.getAuthority).isDefined
  ) yield authority.getAuthority

  def granted(auth: Authentication): Iterable[GrantedAuthority] = for (
    authority <- auth.getAuthorities.asScala
    if Option(authority).isDefined
  ) yield authority


  def userPrincipal(implicit request: HttpServletRequest) = Option(request.getUserPrincipal)
}

class TestSecurity extends Security {
  object TestUser extends User {
    override def orgsFor(action: String, resource: String): Set[String] = Set("1.2.246.562.10.00000000001")
    override val username: String = "Test"
  }

  override def currentUser(implicit request: HttpServletRequest): Option[fi.vm.sade.hakurekisteri.rest.support.User] = Some(TestUser)
}