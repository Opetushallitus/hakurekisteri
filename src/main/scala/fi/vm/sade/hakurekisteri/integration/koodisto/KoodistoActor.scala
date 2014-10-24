package fi.vm.sade.hakurekisteri.integration.koodisto

import java.net.URLEncoder

import akka.actor.{ActorLogging, Actor}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.integration.{FutureCache, PreconditionFailedException, VirkailijaRestClient}

import scala.concurrent.{Future, ExecutionContext}

case class GetRinnasteinenKoodiArvoQuery(koodiUri: String, rinnasteinenKoodistoUri: String)
case class Koodisto(koodistoUri: String)
case class KoodiMetadata(nimi: String, kieli: String)
case class Koodi(koodiArvo: String, koodiUri: String, koodisto: Koodisto, metadata: Seq[KoodiMetadata])
case class RinnasteinenKoodiNotFoundException(message: String) extends Exception(message)

case class GetKoodi(koodistoUri: String, koodiUri: String)

case class KoodistoKoodiArvot(koodistoUri: String, arvot: Seq[String])
case class GetKoodistoKoodiArvot(koodistoUri: String)

class KoodistoActor(restClient: VirkailijaRestClient) extends Actor with ActorLogging {

  implicit val ec: ExecutionContext =  context.dispatcher

  private val koodiCache = new FutureCache[String, Option[Koodi]]()
  private val relaatioCache = new FutureCache[GetRinnasteinenKoodiArvoQuery, String]()
  private val koodiArvotCache = new FutureCache[String, KoodistoKoodiArvot]()
  val maxRetries = 5

  override def receive: Receive = {
    case q: GetRinnasteinenKoodiArvoQuery =>
      getRinnasteinenKoodiArvo(q) pipeTo sender

    case q: GetKoodi =>
      getKoodi(q.koodistoUri, q.koodiUri) pipeTo sender

    case q: GetKoodistoKoodiArvot =>
      getKoodistoKoodiArvot(q.koodistoUri) pipeTo sender
  }

  def getKoodistoKoodiArvot(koodistoUri: String): Future[KoodistoKoodiArvot] = {
    if (koodiArvotCache.contains(koodistoUri)) koodiArvotCache.get(koodistoUri)
    else {
      val f = restClient.readObject[Seq[Koodi]](s"/rest/json/${URLEncoder.encode(koodistoUri, "UTF-8")}/koodi", maxRetries, 200)
        .map(koodit => KoodistoKoodiArvot(koodistoUri, koodit.map(_.koodiArvo)))
      koodiArvotCache + (koodistoUri, f)
      f
    }
  }

  def getKoodi(koodistoUri: String, koodiUri: String): Future[Option[Koodi]] = {
    if (koodiCache.contains(koodiUri)) koodiCache.get(koodiUri)
    else {
      val koodi = restClient.readObject[Koodi](s"/rest/json/${URLEncoder.encode(koodistoUri, "UTF-8")}/koodi/${URLEncoder.encode(koodiUri, "UTF-8")}", maxRetries, 200).map(Some(_)).recoverWith {
        case t: PreconditionFailedException if t.responseCode == 500 =>
          log.warning(s"koodi not found with koodiUri $koodiUri: $t")
          Future.successful(None)
      }
      koodiCache + (koodiUri, koodi)
      koodi
    }
  }

  def getRinnasteinenKoodiArvo(q: GetRinnasteinenKoodiArvoQuery): Future[String] = {
    if (relaatioCache.contains(q)) relaatioCache.get(q)
    else {
      val f: Future[Seq[Koodi]] = restClient.readObject[Seq[Koodi]](s"/rest/json/relaatio/rinnasteinen/${URLEncoder.encode(q.koodiUri, "UTF-8")}", maxRetries, 200)
      val fs = f.map(_.find(_.koodisto.koodistoUri == q.rinnasteinenKoodistoUri) match {
        case None => throw RinnasteinenKoodiNotFoundException(s"rinnasteisia koodeja ei löytynyt koodiurilla ${q.koodiUri}")
        case Some(k) => k.koodiArvo
      })
      relaatioCache + (q, fs)
      fs
    }
  }
}
