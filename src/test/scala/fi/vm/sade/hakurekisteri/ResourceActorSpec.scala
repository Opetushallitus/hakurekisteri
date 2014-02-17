package fi.vm.sade.hakurekisteri

import org.scalatest.WordSpec
import org.scalatest.matchers.ShouldMatchers
import akka.actor.{Props, ActorSystem}
import fi.vm.sade.hakurekisteri.storage.{Identified, ResourceService, ResourceActor}
import java.util.UUID
import fi.vm.sade.hakurekisteri.rest.support.Query
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import org.joda.time.DateTime
import com.github.nscala_time.time.Imports._
import akka.testkit.TestActorRef


class ResourceActorSpec extends WordSpec with ShouldMatchers {
  implicit val system = ActorSystem("test-system")


  implicit val ec = system.dispatcher

  "A resource Actor" when {

    val resourceActor = TestActorRef[TestActor]
    "receiving resource" should {
      val resource = new Resource("foo")
      resourceActor ! resource
      "save it"  in {
        resourceActor.underlyingActor.store should contain (resource)
      }

    }
    "receiving not a resource" should {
      resourceActor ! "foo"
      "not fail" in {

      }
    }

  }

}




