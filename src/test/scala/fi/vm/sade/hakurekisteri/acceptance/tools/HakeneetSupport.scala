package fi.vm.sade.hakurekisteri.acceptance.tools

import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.hakija
import org.scalatra.swagger.Swagger
import fi.vm.sade.hakurekisteri.rest.support.{User, HakurekisteriJsonSupport, HakurekisteriSwagger}
import akka.actor.{Props, ActorSystem}
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import fi.vm.sade.hakurekisteri.hakija.HakijaQuery
import fi.vm.sade.hakurekisteri.hakija.XMLHakemus
import fi.vm.sade.hakurekisteri.hakija.XMLHakutoive
import scala.Some
import org.scalatest.Suite
import org.scalatra.test.HttpComponentsClient
import akka.actor.Status.Success
import akka.actor.FSM.Failure
import scala.concurrent.{Future, ExecutionContext}

trait HakeneetSupport extends Suite with HttpComponentsClient with HakurekisteriJsonSupport {

  object OppilaitosX extends Organisaatio("1.10.1", Map("fi" -> "Oppilaitos X"), None, Some("00001"), None)
  object OppilaitosY extends Organisaatio("1.10.2", Map("fi" -> "Oppilaitos Y"), None, Some("00002"), None)

  object OpetuspisteX extends Organisaatio("1.10.3", Map("fi" -> "Opetuspiste X"), Some("0000101"), None, Some("1.10.1"))
  object OpetuspisteY extends Organisaatio("1.10.4", Map("fi" -> "Opetuspiste Y"), Some("0000201"), None, Some("1.10.2"))

  object FullHakemus1 extends FullHakemus("1.25.1", "ACTIVE", "1.24.1", "1.1",
    Some(Map("kansalaisuus" -> "FIN",
      "asuinmaa" -> "FIN",
      "matkapuhelinnumero1" -> "0401234567",
      "Sukunimi" -> "Mäkinen",
      "Henkilotunnus" -> "200394-9839",
      "Postinumero" -> "00100",
      "lahiosoite" -> "Katu 1",
      "sukupuoli" -> "1",
      "Sähköposti" -> "mikko@testi.oph.fi",
      "Kutsumanimi" -> "Mikko",
      "Etunimet" -> "Mikko",
      "kotikunta" -> "098",
      "aidinkieli" -> "FI",
      "syntymaaika" -> "20.03.1994",
      "onkoSinullaSuomalainenHetu" -> "true",
      "PK_PAATTOTODISTUSVUOSI" -> "2014",
      "POHJAKOULUTUS" -> "1",
      "perusopetuksen_kieli" -> "FI",
      "lahtokoulu" -> OppilaitosX.oid,
      "lahtoluokka" -> "9A",
      "luokkataso" -> "9",
      "preference2-Opetuspiste" -> "Ammattikoulu Lappi2",
      "preference2-Opetuspiste-id" -> "1.10.4",
      "preference2-Koulutus" -> "Musiikin koulutusohjelma, pk (Musiikkialan perustutkinto)4",
      "preference2-Koulutus-id" -> "1.11.2",
      "preference2-Koulutus-id-aoIdentifier" -> "460",
      "preference2-Koulutus-id-educationcode" -> "koulutus_321204",
      "preference2-Koulutus-id-lang" -> "FI",
      "preference1-Opetuspiste" -> "Ammattikoulu Lappi",
      "preference1-Opetuspiste-id" -> "1.10.3",
      "preference1-Koulutus" -> "Musiikin koulutusohjelma, pk (Musiikkialan perustutkinto)",
      "preference1-Koulutus-id" -> "1.11.1",
      "preference1-Koulutus-id-aoIdentifier" -> "460",
      "preference1-Koulutus-id-educationcode" -> "koulutus_321204",
      "preference1-Koulutus-id-lang" -> "FI",
      "lupaMarkkinointi" -> "true",
      "lupaJulkaisu" -> "true"))
  )
  object FullHakemus2 extends FullHakemus("1.25.2", "ACTIVE", "1.24.2", "1.1",
    Some(Map("kansalaisuus" -> "FIN",
      "asuinmaa" -> "FIN",
      "matkapuhelinnumero1" -> "0401234568",
      "Sukunimi" -> "Virtanen",
      "Henkilotunnus" -> "200394-959H",
      "Postinumero" -> "00100",
      "lahiosoite" -> "Katu 2",
      "sukupuoli" -> "1",
      "Sähköposti" -> "ville@testi.oph.fi",
      "Kutsumanimi" -> "Ville",
      "Etunimet" -> "Ville",
      "kotikunta" -> "098",
      "aidinkieli" -> "FI",
      "syntymaaika" -> "20.03.1994",
      "onkoSinullaSuomalainenHetu" -> "true",
      "PK_PAATTOTODISTUSVUOSI" -> "2014",
      "POHJAKOULUTUS" -> "1",
      "perusopetuksen_kieli" -> "FI",
      "lahtokoulu" -> OppilaitosY.oid,
      "lahtoluokka" -> "9A",
      "luokkataso" -> "9",
      "preference2-Opetuspiste" -> "Ammattiopisto Loppi2\"",
      "preference2-Opetuspiste-id" -> "1.10.3",
      "preference2-Koulutus" -> "Musiikin koulutusohjelma, pk (Musiikkialan perustutkinto)2",
      "preference2-Koulutus-id" -> "1.11.1",
      "preference2-Koulutus-id-aoIdentifier" -> "460",
      "preference2-Koulutus-id-educationcode" -> "koulutus_321204",
      "preference2-Koulutus-id-lang" -> "FI",
      "preference1-Opetuspiste" -> "Ammattiopisto Loppi",
      "preference1-Opetuspiste-id" -> "1.10.4",
      "preference1-Koulutus" -> "Musiikin koulutusohjelma, pk (Musiikkialan perustutkinto)",
      "preference1-Koulutus-id" -> "1.11.2",
      "preference1-Koulutus-id-aoIdentifier" -> "460",
      "preference1-Koulutus-id-educationcode" -> "koulutus_321204",
      "preference1-Koulutus-id-lang" -> "FI",
      "lupaMarkkinointi" -> "true",
      "lupaJulkaisu" -> "true"))
  )

  object notEmpty

  implicit def fullHakemus2SmallHakemus(h: FullHakemus): ListHakemus = {
    ListHakemus(h.oid, h.state, h.vastauksetMerged.get("Etunimet"), h.vastauksetMerged.get("Sukunimi"), h.vastauksetMerged.get("Henkilotunnus"), h.personOid)
  }

  import _root_.akka.pattern.ask

  implicit val system = ActorSystem()
  implicit def executor: ExecutionContext = system.dispatcher
  implicit val defaultTimeout = Timeout(60, TimeUnit.SECONDS)


  object hakupalvelu extends Hakupalvelu {


    var tehdytHakemukset: Seq[ListHakemus] = Seq()

    def find(q: HakijaQuery): Future[Seq[ListHakemus]] = q.organisaatio match {
      case Some(OpetuspisteX.oid) => Future(Seq(FullHakemus1))
      case Some(OpetuspisteY.oid) => Future(Seq(FullHakemus2))
    }

    def get(hakemusOid: String, user: Option[User]): Future[Option[FullHakemus]] = hakemusOid match {
      case "1.25.1" => Future(Some(FullHakemus1))
      case "1.25.2" => Future(Some(FullHakemus2))
      case default => Future(None)
    }

    def is(token:Any) = token match {
      case notEmpty => has(FullHakemus1, FullHakemus2)
    }

    def has(hakemukset: ListHakemus*) = {
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

  object koodistopalvelu extends Koodistopalvelu {
    override def getRinnasteinenKoodiArvo(koodiUri: String, rinnasteinenKoodistoUri: String): Future[String] = koodiUri match {
      case _ => Future("246")
    }
  }

  object hakijaResource {
    implicit val swagger: Swagger = new HakurekisteriSwagger



    val hakijaActor = system.actorOf(Props(new HakijaActor(hakupalvelu, organisaatiopalvelu, koodistopalvelu)))

    def get(q: HakijaQuery) = {
      hakijaActor ? q
    }
  }
}
