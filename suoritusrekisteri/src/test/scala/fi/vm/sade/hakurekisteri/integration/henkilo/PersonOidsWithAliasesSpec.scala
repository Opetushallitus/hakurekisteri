package fi.vm.sade.hakurekisteri.integration.henkilo

import fi.vm.sade.hakurekisteri.SpecsLikeMockito
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting
import org.scalatest.concurrent.Waiters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PersonOidsWithAliasesSpec
    extends AnyFlatSpec
    with Matchers
    with FutureWaiting
    with SpecsLikeMockito
    with Waiters {

  private val henkiloOids = Set("1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4")
  private val aliases = Map(
    "1.1.1.1" -> Set("1.1.1.1", "9.9.9.9"),
    "2.2.2.2" -> Set("2.2.2.2"),
    "3.3.3.3" -> Set("3.3.3.3"),
    "4.4.4.4" -> Set("4.4.4.4")
  )
  private val withFourOids = PersonOidsWithAliases(henkiloOids, aliases)

  behavior of "PersonOidsWithAliases"

  it should "combine initial oids with map set" in {
    withFourOids.henkiloOids should have size 4
    withFourOids.aliasesByPersonOids should have size 4
    withFourOids.henkiloOidsWithLinkedOids should have size 5
  }

  it should "be able to filter out other than given person oids" in {
    val withTwoOids = withFourOids.intersect(Set("1.1.1.1", "3.3.3.3"))
    withTwoOids.henkiloOids should have size 2
    withTwoOids.aliasesByPersonOids should have size 2
    withTwoOids.henkiloOidsWithLinkedOids should have size 3
    withTwoOids.aliasesByPersonOids("1.1.1.1") should equal(Set("1.1.1.1", "9.9.9.9"))
  }
}
