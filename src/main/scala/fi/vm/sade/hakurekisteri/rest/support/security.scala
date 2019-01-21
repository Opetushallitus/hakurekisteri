package fi.vm.sade.hakurekisteri.rest.support

import fi.vm.sade.hakurekisteri.{Oids, PohjakoulutusOids}
import fi.vm.sade.hakurekisteri.integration.ytl.YoTutkinto

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
        case "Arvosana" =>  (_) => Set(YoTutkinto.YTL)
        case "Suoritus" => (_) => Set(YoTutkinto.YTL, Oids.cscOrganisaatioOid)
        case "Opiskeluoikeus" => (_) => Set(Oids.cscOrganisaatioOid)
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
  def isKkVirkailija: Boolean = false
  def orgsFor(action: String, resource: String): Set[String]

  def canWrite(resource: String) = !orgsFor("WRITE", resource).isEmpty

  def canDelete(resource: String) = !orgsFor("DELETE", resource).isEmpty

  def canRead(resource: String) = !orgsFor("READ", resource).isEmpty

  def isAdmin: Boolean = orgsFor("DELETE", "Arvosana").contains(Oids.ophOrganisaatioOid)

  def allowByKomo(komo: String, action: String): Boolean = isKkVirkailija && (komo.startsWith("koulutus_") || komo.equals(PohjakoulutusOids.ammatillinen)) && "READ".equals(action)

  def auditSession(): AuditSessionRequest
}

trait Roles {
  val roles: Set[DefinedRole]
}

trait RoleUser extends User with Roles {
  override def orgsFor(action: String, resource: String): Set[String] = roles.collect{
    case DefinedRole(`action`,`resource`, org) => org
  }
}

case class AuditSessionRequest(personOid: String, roles: Set[String], userAgent: String, inetAddress: String)

case class OPHUser(username: String, authorities: Set[String], userAgent: String, inetAddress: String) extends RoleUser {
  override val auditSession = AuditSessionRequest(username, authorities, userAgent, inetAddress)
  override val roles: Set[DefinedRole] = authorities.map(Roles(_).toList).flatten.collect{
    case d: DefinedRole => d
  }
  override def isKkVirkailija: Boolean = authorities.exists(_.startsWith("ROLE_APP_KKHAKUVIRKAILIJA"))
}

//case class BasicUser(username: String, roles: Set[DefinedRole]) extends RoleUser



