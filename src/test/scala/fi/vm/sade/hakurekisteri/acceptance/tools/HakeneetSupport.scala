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
  object OrganisaatioX {
    val oid = "1.10.1"
    val nimi = "Organisaatio X"
    val oppilaitoskoodi = "00001"
  }

  object OrganisaatioY {
    val oid = "1.10.2"
    val nimi = OrganisaatioY
    val oppilaitoskoodi = "00002"
  }

  object Hakemus1 extends Hakemus(2014, "K", "1.25.1", None, None, None, "9", "1", None, None, None, None, None, None, None, Seq(Hakutoive(1, "00001", None, None, "900", None, None, None, None, None, None, None, None, None)), "1.24.1")
  object Hakemus2 extends Hakemus(2014, "K", "1.25.2", None, None, None, "9", "1", None, None, None, None, None, None, None, Seq(Hakutoive(1, "00002", None, None, "190", None, None, None, None, None, None, None, None, None)), "1.24.2")

  object notEmpty

  object hakupalvelu extends Hakupalvelu {

    var tehdytHakemukset: Seq[Hakemus] = Seq()

    def find(q: HakijaQuery): Seq[Hakemus] = {
      if (q.organisaatio == OrganisaatioX.oid)
        Seq(Hakemus1)
      if (q.organisaatio == OrganisaatioY.oid)
        Seq(Hakemus2)
      Seq(Hakemus1, Hakemus2)
    }

    def is(token:Any) = token match {
      case notEmpty => has(Hakemus1, Hakemus2)
    }

    def has(hakemukset: Hakemus*) = {
      tehdytHakemukset = hakemukset
    }

  }

  object henkilopalvelu extends Henkilopalvelu {
    override def find(henkiloOid: String): Option[hakija.Henkilo] = henkiloOid match {
      case "1.24.1" => Some(new hakija.Henkilo("190394-985P", "1.24.1", "Möttönen", "Matti", None, "Katu 1", "00100", "246", "246", None, None, None, None, "1", "FI", true))
      case "1.24.2" => Some(new hakija.Henkilo("190394-951L", "1.24.2", "Möttönen", "Mikko", None, "Katu 1", "00100", "246", "246", None, None, None, None, "1", "FI", true))
    }
  }

  import _root_.akka.pattern.ask

  object hakijaResource {
    implicit val swagger: Swagger = new HakurekisteriSwagger
    implicit val system = ActorSystem()
    implicit def executor: ExecutionContext = system.dispatcher
    implicit val defaultTimeout = Timeout(60, TimeUnit.SECONDS)
    val hakijaActor = system.actorOf(Props(new HakijaActor(hakupalvelu, henkilopalvelu)))

    def get(q: HakijaQuery) = {
      hakijaActor ? q

      //.onComplete {
//        case Success(hakijat) => hakijat
//        case Failure(t) => print("failed: " + t)
//      }
    }
  }
}
