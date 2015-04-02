package fi.vm.sade.hakurekisteri.rest.support

import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.virta.Virta
import fi.vm.sade.hakurekisteri.integration.ytl.YTLXml

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
        case "Arvosana" =>  (_) => Set(YTLXml.YTL)
        case "Suoritus" => (_) => Set(YTLXml.YTL, Virta.CSC)
        case "Opiskeluoikeus" => (_) => Set(Virta.CSC)
      }

    )


  def findSubjects(service: String, org: String)(resource: String) = for (
    serviceResolver <- subjects.lift(service);
    finder <- serviceResolver.lift(resource)
  ) yield finder(org)

  val resources = Set("Arvosana", "Suoritus", "Opiskeluoikeus", "Opiskelija", "Hakukohde", "ImportBatch", "Virta")

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

  def isAdmin:Boolean = orgsFor("DELETE", "Arvosana").contains(Config.ophOrganisaatioOid)


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



