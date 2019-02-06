package fi.vm.sade.hakurekisteri.organization

import akka.actor.ActorSystem
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.dates.{Ajanjakso, InFuture}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus
import fi.vm.sade.hakurekisteri.rest.support.{AuditSessionRequest, User}
import fi.vm.sade.hakurekisteri.web.rest.support.TestUser
import org.apache.commons.lang3.builder.ToStringBuilder
import org.joda.time.DateTime
import org.scalatra.test.scalatest.ScalatraFunSuite
import org.springframework.security.cas.authentication.CasAuthenticationToken

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class ResourceAuthorizerSpec extends ScalatraFunSuite {
  implicit val system = ActorSystem("organization-hierarchy-test-system")
  implicit val timeout: Timeout = 15.seconds

  val organizationAuthorizer: OrganizationAuthorizer = OrganizationAuthorizer(Map())
  val rekisterinpitajaUser = TestUser
  val koulunOrgOid = "koulun_organisaation_oid"
  val koulunVirkailijaUser = new User {
    override def orgsFor(action: String, resource: String): Set[String] = {
      Set(koulunOrgOid)
    }
    override val username: String = "KoulunVirkailija"
    override def auditSession(): AuditSessionRequest = ???
    override def toString: String = ToStringBuilder.reflectionToString(this)
    override def casAuthenticationToken: CasAuthenticationToken = TestUser.casAuthenticationToken
  }
  val testuserOrg = rekisterinpitajaUser.orgsFor("","").head
  private val opiskeluoikeus: Opiskeluoikeus = {
    val ajanjakso = Ajanjakso(new DateTime(), InFuture)
    Opiskeluoikeus(ajanjakso,
      "henkilooid",
      "komo",
      koulunOrgOid,
      "source")
  }
  val resources = Seq(opiskeluoikeus)


  test("kouluvirkailija saa lukea ja kirjoittaa organisaatioluvalla henkilöstä riippumatta") {
    authorizedResourcesForVirkailija(filterWithoutPerson, authSubjectWithOrgButNoPerson) shouldBe Seq(opiskeluoikeus)
    authorizedResourcesForVirkailija(filterWithoutPerson, authSubjectWithOrgAndPerson) shouldBe Seq(opiskeluoikeus)
    authorizedResourcesForVirkailija(filterWithoutPerson, authSubjectWithOrgAndWrongPerson) shouldBe Seq(opiskeluoikeus)

    authorizedResourcesForVirkailija(filterWithoutPerson, authSubjectWithOrgButNoPerson, "WRITE") shouldBe Seq(opiskeluoikeus)
    authorizedResourcesForVirkailija(filterWithoutPerson, authSubjectWithOrgAndPerson, "WRITE") shouldBe Seq(opiskeluoikeus)
    authorizedResourcesForVirkailija(filterWithoutPerson, authSubjectWithOrgAndWrongPerson, "WRITE") shouldBe Seq(opiskeluoikeus)

    authorizedResourcesForVirkailija(filterWithPerson, authSubjectWithOrgButNoPerson) shouldBe Seq(opiskeluoikeus)
    authorizedResourcesForVirkailija(filterWithPerson, authSubjectWithOrgAndPerson) shouldBe Seq(opiskeluoikeus)
    authorizedResourcesForVirkailija(filterWithPerson, authSubjectWithOrgAndWrongPerson) shouldBe Seq(opiskeluoikeus)

    authorizedResourcesForVirkailija(filterWithPerson, authSubjectWithOrgButNoPerson, "WRITE") shouldBe Seq(opiskeluoikeus)
    authorizedResourcesForVirkailija(filterWithPerson, authSubjectWithOrgAndPerson, "WRITE") shouldBe Seq(opiskeluoikeus)
    authorizedResourcesForVirkailija(filterWithPerson, authSubjectWithOrgAndWrongPerson, "WRITE") shouldBe Seq(opiskeluoikeus)

    authorizedResourcesForVirkailija(filterWithAnotherPerson, authSubjectWithOrgButNoPerson) shouldBe Seq(opiskeluoikeus)
    authorizedResourcesForVirkailija(filterWithAnotherPerson, authSubjectWithOrgAndPerson) shouldBe Seq(opiskeluoikeus)
    authorizedResourcesForVirkailija(filterWithAnotherPerson, authSubjectWithOrgAndWrongPerson) shouldBe Seq(opiskeluoikeus)

    authorizedResourcesForVirkailija(filterWithAnotherPerson, authSubjectWithOrgButNoPerson, "WRITE") shouldBe Seq(opiskeluoikeus)
    authorizedResourcesForVirkailija(filterWithAnotherPerson, authSubjectWithOrgAndPerson, "WRITE") shouldBe Seq(opiskeluoikeus)
    authorizedResourcesForVirkailija(filterWithAnotherPerson, authSubjectWithOrgAndWrongPerson, "WRITE") shouldBe Seq(opiskeluoikeus)
  }

  test("rekisterinpitäjä saa lukea ja kirjoittaa henkilöstä riippumatta") {
    authorizedResourcesForRekisterinpitaja(filterWithoutPerson, authSubjectWithOrgButNoPerson) shouldBe Seq(opiskeluoikeus)
    authorizedResourcesForRekisterinpitaja(filterWithoutPerson, authSubjectWithOrgAndPerson) shouldBe Seq(opiskeluoikeus)
    authorizedResourcesForRekisterinpitaja(filterWithoutPerson, authSubjectWithOrgAndWrongPerson) shouldBe Seq(opiskeluoikeus)
    authorizedResourcesForRekisterinpitaja(filterWithoutPerson, authSubjectWithOrgButNoPerson, "WRITE") shouldBe Seq(opiskeluoikeus)
    authorizedResourcesForRekisterinpitaja(filterWithoutPerson, authSubjectWithOrgAndPerson, "WRITE") shouldBe Seq(opiskeluoikeus)
    authorizedResourcesForRekisterinpitaja(filterWithoutPerson, authSubjectWithOrgAndWrongPerson, "WRITE") shouldBe Seq(opiskeluoikeus)
  }

  test("kouluvirkailija ei saa lukea ilman oikeaa organisaatiota tai lupaa henkilöön") {
    authorizedResourcesForVirkailija(filterWithPerson, authSubjectWithWrongOrgAndNoPerson) shouldBe Nil
  }

  test("kouluvirkailija saa lukea mutta ei kirjoittaa väärällä organisaatioluvalla jos lupa henkilöön") {
    authorizedResourcesForVirkailija(filterWithPerson, authSubjectWithWrongOrgButCorrectPerson) shouldBe Seq(opiskeluoikeus)
    authorizedResourcesForVirkailija(filterWithPerson, authSubjectWithWrongOrgButCorrectPerson, "WRITE") shouldBe Nil
  }

  test("kouluvirkailija ei saa lukea luvalla väärään henkilöön") {
    authorizedResourcesForVirkailija(filterWithPerson, authSubjectWithWrongOrgAndWrongPerson) shouldBe Nil
    authorizedResourcesForVirkailija(filterWithAnotherPerson, authSubjectWithWrongOrgAndNoPerson) shouldBe Nil
    authorizedResourcesForVirkailija(filterWithAnotherPerson, authSubjectWithWrongOrgButCorrectPerson) shouldBe Nil
    authorizedResourcesForVirkailija(filterWithAnotherPerson, authSubjectWithWrongOrgAndWrongPerson) shouldBe Nil
  }

  test("is authorized to read, write and delete in same org") {
    val ra = new ResourceAuthorizer[Opiskeluoikeus](filterWithoutPerson, mockAuthorizationSubjectFinder(authSubjectWithOrgButNoPerson))
    Seq("READ", "WRITE", "DELETE").foreach{ action =>
      val isAuthorizedF: Future[Boolean] = ra.isAuthorized(koulunVirkailijaUser, action, opiskeluoikeus)(organizationAuthorizer)
      awaitResult(isAuthorizedF) shouldBe true
    }
  }

  // Ei vielä toteutettu organisaation pohjalta tarkistusta isAuthorizediin, eikä olla vielä varmoja tarvitaanko
  ignore("is authorized to read from other org using auth for person") {
    val ra = new ResourceAuthorizer[Opiskeluoikeus](filterWithoutPerson, mockAuthorizationSubjectFinder(authSubjectWithWrongOrgButCorrectPerson))
    val f: Future[Boolean] = ra.isAuthorized(koulunVirkailijaUser, "READ", opiskeluoikeus)(organizationAuthorizer)
    awaitResult(f) shouldBe true
  }

  test("is not authorized to write or delete to other org using auth for person") {
    val ra = new ResourceAuthorizer[Opiskeluoikeus](filterWithoutPerson, mockAuthorizationSubjectFinder(authSubjectWithWrongOrgButCorrectPerson))
    Seq("WRITE", "DELETE").foreach{ action =>
      val isAuthorizedF: Future[Boolean] = ra.isAuthorized(koulunVirkailijaUser, "WRITE", opiskeluoikeus)(organizationAuthorizer)
      awaitResult(isAuthorizedF) shouldBe false
    }
  }

  private def correctOrg = Set(koulunOrgOid)
  private def noOrg: Set[String] = Set()
  private def wrongOrg = Set("wrong_org_oid")

  private def correctPerson = Some(opiskeluoikeus.henkiloOid)
  private def noPerson: Option[String] = None
  private def wrongPerson = Some("xyzzyx")
  private def anotherPerson = Some("aeiou")

  private def authSubjectWithOrgButNoPerson = authorisationSubject(correctOrg, noPerson)
  private def authSubjectWithOrgAndPerson = authorisationSubject(correctOrg, correctPerson)
  private def authSubjectWithOrgAndWrongPerson = authorisationSubject(correctOrg, wrongPerson)

  private def authSubjectWithWrongOrgAndNoPerson = authorisationSubject(wrongOrg, noPerson)
  private def authSubjectWithWrongOrgButCorrectPerson = authorisationSubject(wrongOrg, correctPerson)
  private def authSubjectWithWrongOrgAndWrongPerson = authorisationSubject(wrongOrg, wrongPerson)

  private def authorisationSubject(personoids: Set[String], personOid: Option[String]): Seq[AuthorizationSubject[Opiskeluoikeus]] = {
    resources.map(x => AuthorizationSubject(item = x, orgs = personoids, personOid = personOid, komo = None))
  }

  private def filterWithoutPerson = filterReturningPersonSet(noPerson.toSet)
  private def filterWithPerson = filterReturningPersonSet(correctPerson.toSet)
  private def filterWithAnotherPerson = filterReturningPersonSet(anotherPerson.toSet)

  private def filterReturningPersonSet(personSet: Set[String]): (User, Set[String]) => Future[Set[String]] = {
    (_, _) => Future.successful(personSet)
  }

  private def authorizedResourcesForVirkailija(filter: (User, Set[String]) => Future[Set[String]], authorizationSubjects: Seq[AuthorizationSubject[Opiskeluoikeus]], action: String = "READ"): Seq[Opiskeluoikeus] = {
    authorizedResources(filter, authorizationSubjects, koulunVirkailijaUser, action)
  }

  private def authorizedResourcesForRekisterinpitaja(filter: (User, Set[String]) => Future[Set[String]], authorizationSubjects: Seq[AuthorizationSubject[Opiskeluoikeus]], action: String = "READ"): Seq[Opiskeluoikeus] = {
    authorizedResources(filter, authorizationSubjects, rekisterinpitajaUser, action)
  }

  private def authorizedResources(filter: (User, Set[String]) => Future[Set[String]], authorizationSubjects: Seq[AuthorizationSubject[Opiskeluoikeus]], virkailijaUser: User, action: String): Seq[Opiskeluoikeus] = {
    val authFinder = mockAuthorizationSubjectFinder(authorizationSubjects)
    val ra = new ResourceAuthorizer[Opiskeluoikeus](filter, authFinder)
    awaitResult(ra.authorizedResources(resources, virkailijaUser, action)(organizationAuthorizer))
  }

  private def awaitResult[A](f: Future[A]): A = {
    Await.result(f, 10.seconds)
  }

  private def mockAuthorizationSubjectFinder(subjects: Seq[AuthorizationSubject[Opiskeluoikeus]]): AuthorizationSubjectFinder[Opiskeluoikeus]  = {
    new AuthorizationSubjectFinder[Opiskeluoikeus] {
      override def apply(v1: Seq[Opiskeluoikeus]): Future[Seq[AuthorizationSubject[Opiskeluoikeus]]] = {
        Future.successful(subjects)
      }
    }
  }
}
