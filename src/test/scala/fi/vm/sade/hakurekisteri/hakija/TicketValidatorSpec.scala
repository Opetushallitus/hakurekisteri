package fi.vm.sade.hakurekisteri.hakija

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import TicketValidator.isValidSt

class TicketValidatorSpec extends FlatSpec with ShouldMatchers {
  behavior of "Ticket Validator"

  it should "return true for a valid ticket" in {
    isValidSt("ST-2192-onNnBnlb5diqMjzO4Ol2-cas.test") should be (true)
  }

  it should "return false for a ticket starting without 'ST-'" in {
    isValidSt("mössöä") should be (false)
  }

  it should "return false for a ticket longer than 256 characters" in {
    isValidSt("ST-Lorem ipsum dolor sit amet, consectetur adipiscing elit. " +
      "Nunc porta, elit eget hendrerit adipiscing, ligula mauris consectetur " +
      "ligula, non condimentum erat elit at risus. Sed aliquet quam eget pellentesque " +
      "ultricies. Nullam feugiat ornare tristique nullam.") should be (false)
  }
}
