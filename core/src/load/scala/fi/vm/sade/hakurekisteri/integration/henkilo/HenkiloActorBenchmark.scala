package fi.vm.sade.hakurekisteri.integration.henkilo

import org.scalameter.api._
import akka.actor.{ActorRef, ActorSystem, Props}
import generators.DataGen
import akka.pattern.ask
import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.duration._
import scala.collection.immutable.IndexedSeq
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.EndpointRequest
import scala.Some
import org.json4s.JsonAST.JString
import com.ning.http.client.AsyncHttpClient


object HenkiloActorBenchmark extends PerformanceTest.OfflineReport {


  val henkiloDataGen: DataGen[CreateHenkilo] = DataGen.values(
    CreateHenkilo(
      etunimet = "Testi Henkilo",
      kutsumanimi = "Testi",
      sukunimi = "Sukuniminen",
      hetu = Some("290499-957B"),
      syntymaaika = Some("1999-04-29"),
      sukupuoli = Some("1"),
      aidinkieli = Some(Kieli("fi")),
      henkiloTyyppi = "OPPIJA",
      kasittelijaOid = "testi",
      organisaatioHenkilo = Seq(OrganisaatioHenkilo("1.2.246.562.5.05127"))
    )
  )

  val henkilos = Gen.exponential("saves")(1,256, 2).map(s => for (
    i <- 0 until s
  ) yield henkiloDataGen.generate)

  val lag = Gen.exponential("henkilo lag")(1,1024,2)



  performance of "HenkiloActor" in {

    measure method "SaveHenkilo" in {

      var system:Option[ActorSystem] = None
      var henkiloActor: Option[ActorRef] = None
      using(Gen.tupled(henkilos, lag)) setUp {
        case (henkiloSeq, lag) =>
          val sys = ActorSystem("perf-test")

          val henkilot: Map[String, String] = henkiloSeq.collect{ case h: CreateHenkilo if h.hetu.isDefined => h.hetu.get}.toSet[String].toList.zipWithIndex.toMap.mapValues((i: String) => s"1.2.246.562.24.$i")

          def url(pattern:String) ="\\$".r.replaceAllIn(s"${pattern.replaceAll("\\?", "\\\\?")}", "(.*)").r


          val endPoint: Endpoint = new Endpoint {
            import org.json4s.jackson.JsonMethods._

            override def request(er: EndpointRequest): (Int, List[(String, String)], String) = er.body.map(parse(_) \ "hetu") match {
              case Some(JString(hetu)) if henkilot.contains(hetu) => (200, List(), henkilot(hetu))
              case _ => (404, List(), "Not Found")
            }



          }
          val asyncProvider =  new DelayingProvider(endPoint, lag.milliseconds)(sys.dispatcher, sys.scheduler)
          val client = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/authentication-service"), Some(new AsyncHttpClient(asyncProvider)))(sys.dispatcher, sys)

          val ha = sys.actorOf(Props(new HenkiloActor(client)))
          system = Some(sys)
          henkiloActor = Some(ha)
      } tearDown {
        _ =>
          system.foreach(_.shutdown())
          system.foreach(_.awaitTermination())
      } in {
        case (henkiloSeq, _) =>
          println(s"sending ${henkiloSeq.size} henkilos to ${henkiloActor.get}")
          val results = for (
            henkilo: CreateHenkilo <- henkiloSeq
          ) yield henkiloActor.get.?(SaveHenkilo(henkilo, henkilo.hetu.get))(15.minutes)
          println(s"saves sent, waiting...")
          implicit val ec: ExecutionContext = system.get.dispatcher
          Await.result(Future.sequence(results), Duration.Inf)
      }


    }
  }

}
