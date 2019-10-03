package fi.vm.sade.hakurekisteri.koodisto

import akka.actor.Actor
import fi.vm.sade.hakurekisteri.integration.koodisto.{GetKoodistoKoodiArvot, KoodistoKoodiArvot}

class MockedKoodistoActor
  extends Actor {
  override def receive: Actor.Receive = {
    case q: GetKoodistoKoodiArvot => q.koodistoUri match {
      case "oppiaineetyleissivistava" => sender ! KoodistoKoodiArvot(
        koodistoUri = "oppiaineetyleissivistava",
        arvot = Seq("AI", "A1", "A12", "A2", "A22", "B1", "B2", "B22", "B23", "B3", "B32", "B33", "BI", "FI","FY", "GE",
          "HI", "KE", "KO", "KS", "KT", "KU", "LI", "MA", "MU", "PS", "TE", "YH")
      )
      case "kieli" => sender ! KoodistoKoodiArvot(
        koodistoUri = "kieli",
        arvot = Seq("FI", "SV", "EN")
      )
    }
  }
}
