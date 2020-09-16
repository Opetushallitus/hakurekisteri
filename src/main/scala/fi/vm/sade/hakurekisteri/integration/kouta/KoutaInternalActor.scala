package fi.vm.sade.hakurekisteri.integration.kouta

import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter

import fi.vm.sade.hakurekisteri.integration.haku.{RestHaku, RestHakuResult}
import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.{ExecutorUtil, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.integration.tarjonta.{GetHautQuery, GetHautQueryFailedException, RestHakuAika}
import org.joda.time.LocalDate
import support.TypedActorRef

import scala.concurrent.{ExecutionContext, Future}

class KoutaInternalActor(restClient: VirkailijaRestClient, config: Config) extends Actor with ActorLogging {
  implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(config.integrations.asyncOperationThreadPoolSize, getClass.getSimpleName)

  override def receive: Receive = {
    case GetHautQuery => getHaut pipeTo sender
  }

  def getHaut: Future[RestHakuResult] = {
    restClient
      .readObject[List[KoutaInternalRestHaku]]("kouta-internal.haku.search.all")(200)
      .map(_.map(_.toRestHaku))
      .map(RestHakuResult).recover {
      case t: Throwable =>
        log.error(t, "error retrieving all hakus from kouta-internal")
        throw GetHautQueryFailedException("error retrieving all hakus from kouta-internal", t)
    }
  }
}

class MockKoutaInternalActor(config: Config) extends KoutaInternalActor(null, config) {}

case class KoutaInternalActorRef(actor: ActorRef) extends TypedActorRef

case class KoutaInternalRestHakuAika(alkaa: String, paattyy: String) {
  def toRestHakuAika: RestHakuAika = {
    val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.of("Europe/Helsinki"))
    RestHakuAika(
      alkuPvm = Instant.from(formatter.parse(alkaa)).toEpochMilli,
      loppuPvm = Some(Instant.from(formatter.parse(paattyy)).toEpochMilli)
    )
  }
}

case class KoutaInternalRestHaku(oid: Option[String],
                                 hakuajat: Option[List[KoutaInternalRestHakuAika]],
                                 nimi: Map[String, String],
                                 alkamiskausiKoodiUri: Option[String],
                                 hakutapaKoodiUri: String,
                                 alkamisvuosi: Option[String],
                                 kohdejoukkoKoodiUri: Option[String],
                                 kohdejoukonTarkenneKoodiUri: Option[String],
                                 tila: String) {
  def toRestHaku: RestHaku = RestHaku(
    oid = oid,
    hakuaikas = hakuajat.map(_.map(_.toRestHakuAika)).getOrElse(List()),
    nimi = nimi,
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

case class KoutaInternalRestHakuResult(result: List[KoutaInternalRestHaku]) {
  def toRestHakuResult: RestHakuResult = RestHakuResult(result = result.map(_.toRestHaku))
}
