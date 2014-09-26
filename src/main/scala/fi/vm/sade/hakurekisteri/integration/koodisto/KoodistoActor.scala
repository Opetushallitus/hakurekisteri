package fi.vm.sade.hakurekisteri.integration.koodisto

import java.net.URLEncoder

import akka.actor.Actor
import akka.event.Logging
import akka.pattern.pipe
import com.stackmob.newman.response.HttpResponseCode
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient

import scala.concurrent.{Future, ExecutionContext}

case class GetRinnasteinenKoodiArvoQuery(koodiUri: String, rinnasteinenKoodistoUri: String)
case class Koodisto(koodistoUri: String)
case class KoodiMetadata(nimi: String, kieli: String)
case class Koodi(koodiArvo: String, koodiUri: String, koodisto: Koodisto, metadata: Seq[KoodiMetadata])
case class RinnasteinenKoodiNotFoundException(message: String) extends Exception(message)

case class GetKoodi(koodistoUri: String, koodiUri: String)

class KoodistoActor(restClient: VirkailijaRestClient)(implicit val ec: ExecutionContext) extends Actor {
  val log = Logging(context.system, this)

  override def receive: Receive = {
    case q: GetRinnasteinenKoodiArvoQuery =>
      getRinnasteinenKoodiArvo(q.koodiUri, q.rinnasteinenKoodistoUri) pipeTo sender

    case q: GetKoodi =>
      getKoodi(q.koodistoUri, q.koodiUri) pipeTo sender
  }

  def getKoodi(koodistoUri: String, koodiUri: String): Future[Option[Koodi]] = {
    try {
      restClient.readObject[Koodi](s"/rest/json/${URLEncoder.encode(koodistoUri, "UTF-8")}/koodi/${URLEncoder.encode(koodiUri, "UTF-8")}", HttpResponseCode.Ok).map(Some(_))
    } catch {
      case t: Throwable => log.warning(s"koodi not found with koodiUri $koodiUri"); Future.successful(None)
    }
  }

  def getRinnasteinenKoodiArvo(koodiUri: String, rinnasteinenKoodistoUri: String): Future[String] = {
    val f: Future[Seq[Koodi]] = restClient.readObject[Seq[Koodi]](s"/rest/json/relaatio/rinnasteinen/${URLEncoder.encode(koodiUri, "UTF-8")}", HttpResponseCode.Ok)
    f.map((koodiList) => {
      if (!koodiList.isEmpty) {
        val filtered = koodiList.filter(_.koodisto.koodistoUri == rinnasteinenKoodistoUri)
        if (!filtered.isEmpty) filtered.head.koodiArvo else throw RinnasteinenKoodiNotFoundException(s"rinnasteista koodia ei löytynyt koodistoon $rinnasteinenKoodistoUri")
      } else {
        throw RinnasteinenKoodiNotFoundException(s"rinnasteisia koodeja ei löytynyt koodiurilla $koodiUri")
      }
    })
  }
}
