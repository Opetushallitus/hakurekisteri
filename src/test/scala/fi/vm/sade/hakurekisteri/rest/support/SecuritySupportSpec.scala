package fi.vm.sade.hakurekisteri.rest.support

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import javax.servlet.http.HttpServletRequest
import org.scalatest.mock.MockitoSugar

class SecuritySupportSpec extends FlatSpec with ShouldMatchers with MockitoSugar {
  behavior of "SecuritySupport.hasAnyRoles"

  implicit val request = mock[HttpServletRequest]

  val securitySupport = new SecuritySupport {
    override def currentUser(implicit request: HttpServletRequest): Option[User] = Some(User(username = "test", authorities = Seq("ROLE_APP_OID_TEST", "ROLE_APP_SUORITUSREKISTERI_CRUD", "ROLE_APP_SUORITUSREKISTERI_CRUD_1.2.246.562.10.00000000001", "ROLE_APP_SUORITUSREKISTERI_READ_UPDATE"), attributePrincipal = None))
  }

  it should "return true when role is allowed" in {
    securitySupport.hasAnyRoles(securitySupport.currentUser, Seq("CRUD")) should be (true)
  }

  it should "return true when any of the roles is allowed" in {
    securitySupport.hasAnyRoles(securitySupport.currentUser, Seq("READ_UPDATE", "READ")) should be (true)
  }

  it should "return false when role is not allowed" in {
    securitySupport.hasAnyRoles(securitySupport.currentUser, Seq("READ")) should be (false)
  }

  it should "return false when the tested role is mapped to a another service" in {
    securitySupport.hasAnyRoles(securitySupport.currentUser, Seq("TEST")) should be (false)
  }

}
