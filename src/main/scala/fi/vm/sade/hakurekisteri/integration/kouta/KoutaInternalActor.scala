package fi.vm.sade.hakurekisteri.integration.kouta

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}

import akka.actor.{Actor, ActorLogging}
import akka.pattern.{AskableActorRef, pipe}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.haku.{
  GetHautQuery,
  RestHaku,
  RestHakuAika,
  RestHakuResult
}
import fi.vm.sade.hakurekisteri.integration.tarjonta.GetHautQueryFailedException
import fi.vm.sade.hakurekisteri.integration.{ExecutorUtil, VirkailijaRestClient}
import org.joda.time.LocalDate
import support.TypedAskableActorRef

import scala.concurrent.{ExecutionContext, Future}

class KoutaInternalActor(restClient: VirkailijaRestClient, config: Config)
    extends Actor
    with ActorLogging {
  implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(
    config.integrations.asyncOperationThreadPoolSize,
    getClass.getSimpleName
  )

  override def receive: Receive = { case GetHautQuery => getHaut pipeTo sender }

  def getHaut: Future[RestHakuResult] =
    restClient
      .readObject[List[KoutaInternalRestHaku]]("kouta-internal.haku.search.all")(200)
      .map(_.map(_.toRestHaku))
      .map(RestHakuResult)
      .recover { case t: Throwable =>
        log.error(t, "error retrieving all hakus from kouta-internal")
        throw GetHautQueryFailedException("error retrieving all hakus from kouta-internal", t)
      }
}

case class KoutaInternalActorRef(actor: AskableActorRef) extends TypedAskableActorRef

class MockKoutaInternalActor(config: Config) extends KoutaInternalActor(null, config)

case class KoutaInternalRestHakuAika(alkaa: String, paattyy: Option[String]) {
  def toRestHakuAika: RestHakuAika = {
    val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.of("Europe/Helsinki"))
    RestHakuAika(
      alkuPvm = Instant.from(formatter.parse(alkaa)).toEpochMilli,
      loppuPvm = paattyy.map(p => Instant.from(formatter.parse(p)).toEpochMilli)
    )
  }
}

case class KoutaInternalRestHaku(
  oid: Option[String],
  tila: String,
  nimi: Map[String, String],
  hakutapaKoodiUri: String,
  kohdejoukkoKoodiUri: Option[String],
  hakuajat: List[KoutaInternalRestHakuAika],
  alkamiskausiKoodiUri: Option[String],
  alkamisvuosi: Option[String],
  kohdejoukonTarkenneKoodiUri: Option[String]
) {
  def toRestHaku: RestHaku = RestHaku(
    oid = oid,
    hakuaikas = hakuajat.map(_.toRestHakuAika),
    nimi = nimi.foldLeft(Map[String, String]())((acc, x) => {
      acc ++ Map(s"kieli_${x._1}" -> x._2)
    }),
    hakukausiUri = alkamiskausiKoodiUri.orNull,
    hakutapaUri = hakutapaKoodiUri,
    hakukausiVuosi = new LocalDate().getYear,
    koulutuksenAlkamiskausiUri = alkamiskausiKoodiUri,
    koulutuksenAlkamisVuosi = alkamisvuosi.map(_.toInt),
    kohdejoukkoUri = kohdejoukkoKoodiUri,
    kohdejoukonTarkenne = kohdejoukonTarkenneKoodiUri,
    tila = tila
  )
}
