package fi.vm.sade.hakurekisteri.rest.support

import javax.servlet.http.HttpServletRequest
import java.security.Principal
import org.springframework.security.core.{GrantedAuthority, Authentication}

sealed trait Role

case class DefinedRole(action: String, resource: String, organization: String) extends Role

object ReadRole {

  def apply(resource: String, organization: String) = DefinedRole("READ", resource, organization)
}



object UnknownRole extends Role

object Roles {


  val subjects: PartialFunction[String, PartialFunction[String, (String) => Set[String]]] =
    Map(
      "SUORITUSREKISTERI" -> {
        case x if resources.contains(x)  => (org: String) => Set(org)
      },
      "KKHAKUVIRKAILIJA" -> {
        case "Arvosana" | "Suoritus" =>  (_) => Set("1.2.246.562.10.43628088406")
      }

    )


  def findSubjects(service: String, org: String)(resource: String) = for (
    serviceResolver <- subjects.lift(service);
    finder <- serviceResolver.lift(resource)
  ) yield finder(org)

  val resources = Set("Arvosana", "Suoritus", "Opiskeluoikeus", "Opiskelija", "Hakukohde")

  def findRoles(finder: (String) => Option[Set[String]])(actions: Set[String]): Set[DefinedRole] = {
    for (
      action <- actions;
      resource <- resources;
      subject <- finder(resource).getOrElse(Set())
    ) yield DefinedRole(action, resource,  subject)
  }


  def apply(authority:String) =  authority match {
    case role(service, right, org) =>
      def roleFinder(roles: String*):Set[DefinedRole] = findRoles(findSubjects(service, org))(roles.toSet)
      right match {
        case "CRUD" =>  roleFinder("DELETE", "WRITE", "READ")

        case "READ_UPDATE" => roleFinder("WRITE", "READ")
        case "READ" => roleFinder("READ")
        case _ => Set(UnknownRole)
      }
    case _ => Set(UnknownRole)
  }




  val role = "ROLE_APP_([^_]*)_(.*)_(\\d+\\.\\d+\\.\\d+\\.\\d+\\.\\d+\\.\\d+)".r


}

trait User {
  val username: String
  def orgsFor(action: String, resource: String): Set[String]

  def canWrite(resource: String) = !orgsFor("WRITE", resource).isEmpty

  def canDelete(resource: String) = !orgsFor("DELETE", resource).isEmpty

  def canRead(resource: String) = !orgsFor("READ", resource).isEmpty


}

trait Roles {

  val roles: Set[DefinedRole]

}

trait RoleUser extends User with Roles {

  override def orgsFor(action: String, resource: String): Set[String] = roles.collect{
    case DefinedRole(`action`,`resource`, org) => org
  }

}

case class OPHUser(username: String, authorities: Set[String]) extends RoleUser {


  override val roles: Set[DefinedRole] = authorities.map(Roles(_).toList).flatten.collect{
    case d: DefinedRole => d
  }

}

case class BasicUser(username: String, roles: Set[DefinedRole]) extends RoleUser



trait SecuritySupport {
  def currentUser(implicit request: HttpServletRequest): Option[User]

}

trait SpringSecuritySupport extends SecuritySupport {
  import scala.collection.JavaConverters._

  def currentUser(implicit request: HttpServletRequest): Option[User] =  for(
    user: Principal <- userPrincipal
  ) yield user match {
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
