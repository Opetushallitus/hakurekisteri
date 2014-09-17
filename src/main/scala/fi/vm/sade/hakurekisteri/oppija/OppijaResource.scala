package fi.vm.sade.hakurekisteri.oppija

import fi.vm.sade.hakurekisteri.rest.support.{SpringSecuritySupport, HakurekisteriJsonSupport, Registers}
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.{AsyncResult, CorsSupport, FutureSupport}
import akka.actor.{ActorSystem, ActorRef}
import scala.concurrent.{Future, ExecutionContext}
import akka.util.Timeout
import org.scalatra.swagger.Swagger
import fi.vm.sade.hakurekisteri.hakija.HakijaQuery
import fi.vm.sade.hakurekisteri.integration.hakemus.{FullHakemus, HakemusQuery}
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusQuery}
import akka.pattern.ask
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaQuery}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusQuery}
import fi.vm.sade.hakurekisteri.ensikertalainen.{Ensikertalainen, EnsikertalainenQuery}
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, ArvosanaQuery}
import fi.vm.sade.hakurekisteri.storage.Identified
import java.util.UUID


class OppijaResource(rekisterit: Registers, hakemusRekisteri: ActorRef, ensikertalaisuus: ActorRef)(implicit system: ActorSystem, sw: Swagger) extends HakuJaValintarekisteriStack  with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with CorsSupport with SpringSecuritySupport {

  override protected implicit def executor: ExecutionContext = system.dispatcher

  options("/*") {
    response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
  }

  before() {
    contentType = formats("json")
  }

  import scala.concurrent.duration._

  implicit val defaultTimeout: Timeout = 60.seconds


  get("/") {

    import akka.pattern.ask
    val q = HakijaQuery(params, currentUser)






    new AsyncResult() {
      override implicit def timeout: Duration = 500.seconds
      val is = for (
        hakemukset <- (hakemusRekisteri ? HakemusQuery(q)).mapTo[Seq[FullHakemus]];
        oppijat <- fetchOppijatFor(hakemukset)
      ) yield oppijat
    }
  }


  def fetchOppijatFor(hakemukset: Seq[FullHakemus]): Future[Seq[Oppija]] =
    Future.sequence(for (
      hakemus <- hakemukset
    ) yield fetchOppijaData(hakemus.oid, hakemus.hetu))


  def fetchTodistukset(suoritukset: Seq[Suoritus with Identified[UUID]]):Future[Seq[Todistus]] = Future.sequence(
    for (
      suoritus <- suoritukset
    ) yield for (
        arvosanat <- (rekisterit.arvosanaRekisteri ? ArvosanaQuery(suoritus = Some(suoritus.id))).mapTo[Seq[Arvosana]]
      ) yield Todistus(suoritus, arvosanat))

  def fetchOppijaData(henkiloOid: String, hetu: Option[String]): Future[Oppija] = {
    for (
      suoritukset <- (rekisterit.suoritusRekisteri ? SuoritusQuery(henkilo = Some(henkiloOid))).mapTo[Seq[Suoritus with Identified[UUID]]];
      todistukset <- fetchTodistukset(suoritukset);
      opiskelu <- (rekisterit.opiskelijaRekisteri ? OpiskelijaQuery(henkilo = Some(henkiloOid))).mapTo[Seq[Opiskelija]];
      opiskeluoikeudet <- (rekisterit.opiskeluoikeusRekisteri ? OpiskeluoikeusQuery(henkilo = Some(henkiloOid))).mapTo[Seq[Opiskeluoikeus]];
      ensikertalainen <- (ensikertalaisuus ? EnsikertalainenQuery(henkiloOid, hetu)).mapTo[Ensikertalainen]
    ) yield Oppija(
      oppijanumero = henkiloOid,
      opiskelu = opiskelu,
      suoritukset = todistukset,
      opiskeluoikeudet = opiskeluoikeudet,
      ensikertalainen = ensikertalainen.ensikertalainen
    )

  }
}
