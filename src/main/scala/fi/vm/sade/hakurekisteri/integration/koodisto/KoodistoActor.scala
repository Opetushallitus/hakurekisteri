package fi.vm.sade.hakurekisteri.integration.koodisto

import java.net.URLEncoder
import java.util.concurrent.ExecutionException

import akka.actor.{Actor, ActorLogging}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.cache.CacheFactory
import fi.vm.sade.hakurekisteri.integration.{OphUrlProperties, PreconditionFailedException, VirkailijaRestClient}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

case class GetRinnasteinenKoodiArvoQuery(koodisto: String, arvo: String, rinnasteinenKoodistoUri: String)
case class Koodisto(koodistoUri: String)
case class KoodiMetadata(nimi: String, kieli: String)
case class Koodi(koodiArvo: String, koodiUri: String, koodisto: Koodisto, metadata: Seq[KoodiMetadata])
case class RinnasteinenKoodiNotFoundException(message: String) extends Exception(message)

case class GetKoodi(koodistoUri: String, koodiUri: String)

case class KoodistoKoodiArvot(koodistoUri: String, arvot: Seq[String])
case class GetKoodistoKoodiArvot(koodistoUri: String)

class KoodistoActor(restClient: VirkailijaRestClient, config: Config, cacheFactory: CacheFactory) extends Actor with ActorLogging {

  implicit val ec: ExecutionContext =  context.dispatcher

  private val koodiCache = cacheFactory.getInstance[String, Option[Koodi]](config.integrations.koodistoCacheHours.hours.toMillis, getClass, "koodi")
  private val relaatioCache = cacheFactory.getInstance[GetRinnasteinenKoodiArvoQuery, String](config.integrations.koodistoCacheHours.hours.toMillis, getClass, "relaatio")
  private val koodiArvotCache = cacheFactory.getInstance[String, KoodistoKoodiArvot](config.integrations.koodistoCacheHours.hours.toMillis, getClass, "koodi-arvo")
  val maxRetries = config.integrations.koodistoConfig.httpClientMaxRetries

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
      val f = restClient.readObject[Seq[Koodi]]("koodisto-service.koodisByKoodisto", koodistoUri)(200, maxRetries)
        .map(koodit => KoodistoKoodiArvot(koodistoUri, koodit.map(_.koodiArvo)))
      koodiArvotCache + (koodistoUri, f)
      f
    }
  }

  def notFound(t: Throwable): Boolean = t match {
    case PreconditionFailedException(_, 500) => true
    case _ => false
  }

  def getKoodi(koodistoUri: String, koodiUri: String): Future[Option[Koodi]] = {
    if (koodiCache.contains(koodiUri)) koodiCache.get(koodiUri)
    else {
      val koodi = restClient.readObject[Koodi]("koodisto-service.koodiByUri", koodistoUri, koodiUri)(200, maxRetries).map(Some(_)).recoverWith {
        case t: ExecutionException if t.getCause != null && notFound(t.getCause) =>
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
      lazy val url = OphUrlProperties.url("koodisto-service.koodisByKoodistoAndArvo", q.koodisto, q.arvo)
      restClient.readObject[Seq[Koodi]]("koodisto-service.koodisByKoodistoAndArvo", q.koodisto, q.arvo)(200, maxRetries)
          .map(_.headOption.map(_.koodiUri)).flatMap(uriOpt => {
            uriOpt match {
              case Some(uri) =>
                val fs = restClient.readObject[Seq[Koodi]]("koodisto-service.relaatio", "rinnasteinen", uri)(200, maxRetries)
                  .map(_.find(_.koodisto.koodistoUri == q.rinnasteinenKoodistoUri) match {
                    case None => throw RinnasteinenKoodiNotFoundException(s"rinnasteisia koodeja ei löytynyt koodiurilla $uri")
                    case Some(k) => k.koodiArvo
                  })
                relaatioCache + (q, fs)
                fs
              case _ =>
                throw RinnasteinenKoodiNotFoundException(s"rinnasteisia koodeja ei löytynyt koodistosta: $url")
            }
        })
    }
  }
}
