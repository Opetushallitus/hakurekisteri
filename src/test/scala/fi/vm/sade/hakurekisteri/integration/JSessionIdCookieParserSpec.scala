package fi.vm.sade.hakurekisteri.integration

import org.scalatest.{Matchers, FlatSpec}

class JSessionIdCookieParserSpec extends FlatSpec with Matchers {

  behavior of "JSessionIdCookieParser"

  it should "parse JSESSIONID cookie" in {
    val c = "JSESSIONID=abcd"

    val cookie = JSessionIdCookieParser.fromString(c, "JSESSIONID")

    cookie.sessionId should be ("abcd")
  }

  it should "not fail with cookie options" in {
    val c = "JSESSIONID=abcd; Path=/; Secure; HttpOnly"

    val cookie = JSessionIdCookieParser.fromString(c, "JSESSIONID")

    cookie.sessionId should be ("abcd")
  }

  it should "throw JSessionIdCookieException on other cookies" in {
    val c = "test=foo"

    intercept[JSessionIdCookieException] {
      JSessionIdCookieParser.fromString(c, "JSESSIONID")
    }
  }

  it should "throw JSessionIdCookieException on invalid cookie" in {
    val c = "foobar"

    intercept[JSessionIdCookieException] {
      JSessionIdCookieParser.fromString(c, "JSESSIONID")
    }
  }

  it should "read other types of session cookies" in {
    val c = "ring-session=asdasd"

    val cookie = JSessionIdCookieParser.fromString(c, "ring-session")

    cookie.sessionId should be ("asdasd")  }
}
