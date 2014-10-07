package fi.vm.sade.hakurekisteri.rest.support

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import javax.servlet.http.HttpServletRequest
import org.scalatest.mock.MockitoSugar
import scala.util.DynamicVariable

class SecuritySupportSpec extends FlatSpec with ShouldMatchers with MockitoSugar {
  behavior of "User rights"

  import SecurityUser._

  it should "allow read for CRUD user" in
    securitySession.withLoginBy(user having ("CRUD", "READ_UPDATE", "READ").rights)
      {currentUser.canRead should be (true)}

  it should "allow read for READ_UPDATE user" in
    securitySession.withLoginBy(user having ("READ_UPDATE", "READ").rights)
      {currentUser.canRead should be (true)}

  it should "allow read for READ user" in
    securitySession.withLoginBy(user having "READ".rights)
    {currentUser.canRead should be (true)}

  it should "allow write for CRUD user" in
    securitySession.withLoginBy(user having ("CRUD", "READ_UPDATE", "READ").rights)
    {currentUser.canWrite should be (true)}

  it should "allow write for READ_UPDATE user" in
    securitySession.withLoginBy(user having ("READ_UPDATE", "READ").rights)
    {currentUser.canWrite should be (true)}

  it should "not allow write for READ user" in
    securitySession.withLoginBy(user having "READ".rights)
    {currentUser.canWrite should be (false)}

  it should "allow delete for CRUD user" in
    securitySession.withLoginBy(user having ("CRUD", "READ_UPDATE", "READ").rights)
    {currentUser.canWrite should be (true)}

  it should "not allow delete for READ_UPDATE user" in
    securitySession.withLoginBy(user having ("READ_UPDATE", "READ").rights)
    {currentUser.canDelete should be (false)}

  it should "not allow delete for READ user" in
    securitySession.withLoginBy(user having "READ".rights)
    {currentUser.canDelete should be (false)}




}


object SecurityUser {
  object  user {

    def having(rights:Seq[DefinedRole]) = User(username = "test",
      authorities = rights.map{case DefinedRole(service, right, org) => s"ROLE_APP_${service}_${right}_${org}"},
      attributePrincipal = None)
  }

  object securitySession {
    def withLoginBy(currentUser:User)(test: => Unit) = dynamicUser.withValue[Unit](currentUser)(test)
  }


  case class Rights(grantedRights: Seq[String]) {
    def rights: List[DefinedRole] = grantedRights.map((right) => DefinedRole("SUORITUSREKISTERI", right, "1.2.246.562.10.00000000001")).toList
  }

  implicit def tuple2Rigths(grantedRights: Product):Rights = Rights(grantedRights.productIterator.map(_.toString).toList)

  implicit def stringToRights(granted: String):Rights = Rights(Seq(granted))
  implicit def currentUser: User = dynamicUser.value

  private[this] val dynamicUser = new DynamicVariable[User](null)

}