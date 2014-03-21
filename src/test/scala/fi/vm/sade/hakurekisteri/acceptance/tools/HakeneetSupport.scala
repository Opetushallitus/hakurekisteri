package fi.vm.sade.hakurekisteri.acceptance.tools

import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.hakija
import org.scalatra.swagger.Swagger
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, HakurekisteriSwagger}
import akka.actor.{Props, ActorSystem}
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import fi.vm.sade.hakurekisteri.hakija.HakijaQuery
import fi.vm.sade.hakurekisteri.hakija.Hakemus
import fi.vm.sade.hakurekisteri.hakija.Hakutoive
import scala.Some
import org.scalatest.Suite
import org.scalatra.test.HttpComponentsClient
import akka.actor.Status.Success
import akka.actor.FSM.Failure
import scala.concurrent.{Future, ExecutionContext}

trait HakeneetSupport extends Suite with HttpComponentsClient with HakurekisteriJsonSupport {

  object OppilaitosX extends Organisaatio("1.10.1", Map("fi" -> "Oppilaitos X"), "", "00001")
  object OppilaitosY extends Organisaatio("1.10.2", Map("fi" -> "Oppilaitos Y"), "", "00002")

  object OpetuspisteX extends Organisaatio("1.10.3", Map("fi" -> "Opetuspiste X"), "0000301", "")
  object OpetuspisteY extends Organisaatio("1.10.4", Map("fi" -> "Opetuspiste Y"), "0000401", "")

  object FullHakemus1 extends FullHakemus("1.25.1", "ACTIVE", "1.24.1", "1.26.1", "1.24.1", 1, 1,
    Answers(Henkilotiedot("FIN", "FIN", "0401234567", "Mäkinen", "200394-9839", "00100", "Katu 1", "1", "mikko@testi.oph.fi", "Mikko", "Mikko", "098", "FI", "20.03.1994"),
      Koulutustausta("2014", "1", "FI", Some(OppilaitosX.oid), Some("9A"), "9"), Map(
        "preference1-Opetuspiste" -> "Ammattikoulu Lappi",
        "preference1-Opetuspiste-id" -> "1.10.3",
        "preference1-Koulutus" -> "Musiikin koulutusohjelma, pk (Musiikkialan perustutkinto)",
        "preference1-Koulutus-id" -> "1.11.1",
        "preference1-Koulutus-id-aoIdentifier" -> "460",
        "preference1-Koulutus-id-educationcode" -> "koulutus_321204",
        "preference1-Koulutus-id-lang" -> "FI"
      ), Lisatiedot(true, Some(true))
    )
  )
  object FullHakemus2 extends FullHakemus("1.25.2", "ACTIVE", "1.24.2", "1.26.1", "1.24.2", 1, 1,
    Answers(Henkilotiedot("FIN", "FIN", "0401234568", "Virtanen", "200394-959H", "00100", "Katu 2", "1", "ville@testi.oph.fi", "Ville", "Ville", "098", "FI", "20.03.1994"),
      Koulutustausta("2014", "1", "FI", Some(OppilaitosY.oid), Some("9A"), "9"), Map(
        "preference1-Opetuspiste" -> "Ammattiopisto Loppi",
        "preference1-Opetuspiste-id" -> "1.10.4",
        "preference1-Koulutus" -> "Musiikin koulutusohjelma, pk (Musiikkialan perustutkinto)",
        "preference1-Koulutus-id" -> "1.11.2",
        "preference1-Koulutus-id-aoIdentifier" -> "460",
        "preference1-Koulutus-id-educationcode" -> "koulutus_321204",
        "preference1-Koulutus-id-lang" -> "FI"
      ), Lisatiedot(true, Some(true))
    )
  )

  object notEmpty



  import _root_.akka.pattern.ask

  implicit val system = ActorSystem()
  implicit def executor: ExecutionContext = system.dispatcher
  implicit val defaultTimeout = Timeout(60, TimeUnit.SECONDS)


  object hakupalvelu extends Hakupalvelu {


    var tehdytHakemukset: Seq[SmallHakemus] = Seq()

    def find(q: HakijaQuery): Future[Seq[SmallHakemus]] = q.organisaatio match {
      case Some(OpetuspisteX.oid) => Future(Seq(FullHakemus1.toSmallHakemus))
      case Some(OpetuspisteY.oid) => Future(Seq(FullHakemus2.toSmallHakemus))
    }

    def get(hakemusOid: String): Future[Option[FullHakemus]] = hakemusOid match {
      case "1.25.1" => Future(Some(FullHakemus1))
      case "1.25.2" => Future(Some(FullHakemus2))
      case default => Future(None)
    }

    def is(token:Any) = token match {
      case notEmpty => has(FullHakemus1.toSmallHakemus, FullHakemus2.toSmallHakemus)
    }

    def has(hakemukset: SmallHakemus*) = {
      tehdytHakemukset = hakemukset
    }
  }

  object organisaatiopalvelu extends Organisaatiopalvelu {
    override def get(str: String): Future[Option[Organisaatio]] = Future(doTheMatch(str))

    def doTheMatch(str: String): Option[Organisaatio] = str match {
      case OppilaitosX.oid => Some(OppilaitosX)
      case OppilaitosY.oid => Some(OppilaitosY)
      case OpetuspisteX.oid => Some(OpetuspisteX)
      case OpetuspisteY.oid => Some(OpetuspisteY)
      case default => None
    }
  }

  object hakijaResource {
    implicit val swagger: Swagger = new HakurekisteriSwagger



    val hakijaActor = system.actorOf(Props(new HakijaActor(hakupalvelu, organisaatiopalvelu)))

    def get(q: HakijaQuery) = {
      hakijaActor ? q
    }
  }
}
