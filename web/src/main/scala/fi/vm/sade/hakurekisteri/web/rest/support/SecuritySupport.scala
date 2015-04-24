package fi.vm.sade.hakurekisteri.web.rest.support

import java.security.Principal
import javax.servlet.http.HttpServletRequest

import fi.vm.sade.hakurekisteri.rest.support.{OPHUser, User}
import org.springframework.security.core.{Authentication, GrantedAuthority}


trait SecuritySupport {
  def currentUser(implicit request: HttpServletRequest): Option[User]
}

trait SpringSecuritySupport extends SecuritySupport {
  import scala.collection.JavaConverters._

  def currentUser(implicit request: HttpServletRequest): Option[User] = userPrincipal.map {
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
