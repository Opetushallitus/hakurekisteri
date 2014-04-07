package fi.vm.sade.hakurekisteri.hakija

import scala.concurrent.{Future, ExecutionContext}
import org.slf4j.LoggerFactory
import com.stackmob.newman.ApacheHttpClient
import java.net.URL
import com.stackmob.newman.dsl._
import com.stackmob.newman.response.HttpResponseCode

case class Koodisto(koodistoUri: String)
case class Koodi(koodiArvo: String, koodiUri: String, koodisto: Koodisto)

trait Koodistopalvelu {

  def getRinnasteinenKoodiArvo(koodiUri: String, rinnasteinenKoodistoUri: String): Future[String]

}

class RestKoodistopalvelu(serviceUrl: String = "https://itest-virkailija.oph.ware.fi/koodisto-service")(implicit val ec: ExecutionContext) extends Koodistopalvelu {
  val logger = LoggerFactory.getLogger(getClass)

  implicit val httpClient = new ApacheHttpClient

  override def getRinnasteinenKoodiArvo(koodiUri: String, rinnasteinenKoodistoUri: String): Future[String] = {
    val url = new URL(serviceUrl + "/rest/json/relaatio/rinnasteinen/" + koodiUri)
    logger.debug("calling koodisto-service [{}]", url)
    GET(url).apply.map(response => {
      if (response.code == HttpResponseCode.Ok) {
        val kooditOption = response.bodyAsCaseClass[Seq[Koodi]].toOption
        logger.debug("got response: [{}]", kooditOption)
        kooditOption.map((koodit) => {
          if (!koodit.isEmpty) {
            val x = koodit.filter((koodi: Koodi) => koodi.koodisto.koodistoUri == rinnasteinenKoodistoUri)
            if (!x.isEmpty) x.head.koodiArvo else throw new RuntimeException("rinnasteista koodia ei löytynyt")
          } else {
            throw new RuntimeException("rinnasteista koodia ei löytynyt")
          }
        }).get
      } else {
        logger.error("call to koodisto-service [{}] failed: {}", Array(url, response.code))
        throw new RuntimeException("virhe kutsuttaessa koodistopalvelua: %s".format(response.code))
      }
    })
  }

}