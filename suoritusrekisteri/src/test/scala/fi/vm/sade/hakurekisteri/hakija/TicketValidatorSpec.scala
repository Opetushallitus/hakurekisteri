package fi.vm.sade.hakurekisteri.hakija

import fi.vm.sade.hakurekisteri.integration.cas.TicketValidator

import TicketValidator.isValidSt
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TicketValidatorSpec extends AnyFlatSpec with Matchers {
  behavior of "Ticket Validator"

  it should "return true for a valid ticket" in {
    isValidSt("ST-2192-onNnBnlb5diqMjzO4Ol2-cas.test") should be(true)
  }

  it should "return false for a ticket starting without 'ST-'" in {
    isValidSt("mössöä") should be(false)
  }

  it should "return false for a ticket longer than 256 characters" in {
    isValidSt(
      "ST-Lorem ipsum dolor sit amet, consectetur adipiscing elit. " +
        "Nunc porta, elit eget hendrerit adipiscing, ligula mauris consectetur " +
        "ligula, non condimentum erat elit at risus. Sed aliquet quam eget pellentesque " +
        "ultricies. Nullam feugiat ornare tristique nullam."
    ) should be(false)
  }
}
