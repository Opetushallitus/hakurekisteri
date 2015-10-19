package fi.vm.sade.hakurekisteri.integration.valintarekisteri

import java.net.URLEncoder

import akka.actor.Actor
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import org.joda.time.DateTime

import scala.concurrent.{ExecutionContext, Future}


class ValintarekisteriActor(restClient: VirkailijaRestClient, config: Config)  extends Actor {

  implicit val ec: ExecutionContext = context.dispatcher

  override def receive: Receive = {
    case ValintarekisteriQuery(henkiloOid, koulutuksenAlkamispmv) => pipe(fetchEnsimmainenVastaanotto(henkiloOid, koulutuksenAlkamispmv)) pipeTo sender
  }

  def fetchEnsimmainenVastaanotto(henkiloOid: String, koulutuksenAlkamispvm: DateTime): Future[Option[DateTime]] = restClient.readObject[EnsimmainenVastaanotto](s"/ensikertalaisuus/$henkiloOid?koulutuksenAlkamispvm=${URLEncoder.encode(koulutuksenAlkamispvm.toDateTimeISO.toString, "UTF-8")}", 200).map(_.paattyi)

}

case class ValintarekisteriQuery(henkiloOid: String, koulutuksenAlkamispvm: DateTime)

case class EnsimmainenVastaanotto(oid: String, paattyi: Option[DateTime])
