package fi.vm.sade.hakurekisteri.organization

import java.util.concurrent.Executors

import fi.vm.sade.hakurekisteri.rest.support.User
import fi.vm.sade.utils.slf4j.Logging

import scala.concurrent.{ExecutionContext, Future}

class ResourceAuthorizer[A](filterOppijaOidsForHakemusBasedReadAccess: (User, Set[String]) => Future[Set[String]],
                            authorizationSubjectFinder: AuthorizationSubjectFinder[A])(implicit m: Manifest[A]) extends Logging {
  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(10))

  def authorizedResources(resources: Seq[A], user: User, action: String)(organizationAuthorizer: OrganizationAuthorizer): Future[Seq[A]] = {
    subjectFinder(resources).map {
      _.map {
        case (item, subject) => (item, subject, organizationAuthorizer.checkAccess(user, action, subject))
      }
    }.flatMap { xs =>
      val entriesNotAuthorizedByOrganization = xs.filter(_._3 == false)
      val oppijaOidsForHakemusBasedAccess: Future[Set[String]] = if (entriesNotAuthorizedByOrganization.nonEmpty && action == "READ") {
        val uniqueOppijaOids: Set[String] = entriesNotAuthorizedByOrganization.flatMap(_._2.oppijaOid).toSet
        if (user.isAdmin) {
          logger.warn(s"Some data fell back to hakemus based read access check for admin user ${user.username} ! Looks like a bug.")
          logger.warn(s"About to invoke hakemus based read access check for ${uniqueOppijaOids.size} " +
            s"oids when checking READ access for user ${user.username} and ${entriesNotAuthorizedByOrganization.map(x => x._1 + " : " + x._2).mkString(", ")}")
        }
        filterOppijaOidsForHakemusBasedReadAccess(user, uniqueOppijaOids)
      } else {
        Future.successful(Set())
      }
      Future.successful(xs).zip(oppijaOidsForHakemusBasedAccess)
    }.map {
      case (xs, oppijasAllowedByHakemus) => xs.collect {
        case (x, subject, allowedByOrgs) if allowedByOrgs || subject.oppijaOid.exists(oppijasAllowedByHakemus) => x
      }
    }
  }

  def isAuthorized(user:User, action: String, item: A)(organizationAuthorizer: OrganizationAuthorizer): concurrent.Future[Boolean] =
    subjectFinder(Seq(item)).map {
      case (_, subject) :: _ => organizationAuthorizer.checkAccess(user, action, subject)
    }

  private def subjectFinder(resources: Seq[A])(implicit m: Manifest[A]): Future[Seq[(A, Subject)]] = {
    authorizationSubjectFinder(resources).map(_.map(o => (o.item, Subject(m.runtimeClass.getSimpleName, o.orgs, oppijaOid = o.personOid, komo = o.komo))))
  }
}
