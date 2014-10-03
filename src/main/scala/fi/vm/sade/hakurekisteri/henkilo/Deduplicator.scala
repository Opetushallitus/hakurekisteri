package fi.vm.sade.hakurekisteri.henkilo

import akka.actor.{Props, ActorRef, Actor}
import com.stackmob.newman.dsl._
import java.net.URL
import com.stackmob.newman.ApacheHttpClient
import com.stackmob.newman.response.HttpResponseCode
import akka.event.Logging
import scala.concurrent.{Promise, Future}
import fi.vm.sade.hakurekisteri.suoritus.{VapaamuotoinenSuoritus, VirallinenSuoritus, Suoritus, SuoritusQuery}
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaQuery}
import fi.vm.sade.hakurekisteri.storage.Identified
import java.util.UUID
import scala.collection.mutable

/**
 * Created by verneri on 19.5.2014.
 */
class Deduplicator(suoritusRekisteri:ActorRef, opiskelijaRekisteri:ActorRef) extends Actor {

  implicit val ec=  context.dispatcher

  implicit val client: ApacheHttpClient = new ApacheHttpClient()

  val log = Logging(context.system, this)
  override def receive: Actor.Receive = {

    case Deduplicate(url) =>
      for (dedup <- deduplicateOn(url))
        for (r <- dedup) Person(r.henkiloOid) deduplicateTo r.masterOid


  }
  import scala.concurrent.duration._

  def deduplicateOn(url: URL): Future[Seq[DeduplicatorResult]] = {
    GET(url).apply.map(response => if (response.code == HttpResponseCode.Ok) {
      response.bodyAsCaseClass[List[DeduplicatorResult]].toOption.getOrElse(Seq())
    } else {
      log.warning(s"no connection to $url")
      context.system.scheduler.scheduleOnce(1.minute, self, Deduplicate(url))
      Seq()
    })
  }

  case class Person(henkiloOid:String) {
    def deduplicateTo(newOid:String) {
      context.actorOf(Props(new Actor {

        val updatedSuoritukset = mutable.Map[UUID, Promise[Unit]]()
        val updatedOpiskelijat = mutable.Map[UUID, Promise[Unit]]()
        val sentOpiskelijat = Promise[Unit]
        val sentSuoritukset = Promise[Unit]
        val opiskelijaFuture = sentOpiskelijat.future.flatMap((_) => Future.sequence(updatedSuoritukset.values.map(_.future)).map(_.size))
        val suoritusFuture = sentSuoritukset.future.flatMap((_) => Future.sequence(updatedOpiskelijat.values.map(_.future)).map(_.size))
        val done = Future.sequence(Seq(opiskelijaFuture, suoritusFuture)).map(_ match { case opiskelijat::suoritukset::tail => Updated(opiskelijat, suoritukset)})

        val retry = context.system.scheduler.schedule(1.minute, 1.minute)(askForDuplicates)

        def handleSuoritukset(suoritukset: Seq[Suoritus with Identified[UUID]]) {
          for (suoritus <- suoritukset)
            if (suoritus.henkiloOid != newOid) suoritus match {
              case s: VirallinenSuoritus with Identified[UUID] =>
                suoritusRekisteri ! VirallinenSuoritus(s.komo: String, s.myontaja: String, s.tila: String, s.valmistuminen, newOid, s.yksilollistaminen, s.suoritusKieli, s.opiskeluoikeus, s.vahvistettu, s.source).identify(s.id)
                updatedSuoritukset.put(s.id, Promise[Unit])
              case s: VapaamuotoinenSuoritus with Identified[UUID] =>
                suoritusRekisteri ! VapaamuotoinenSuoritus(newOid, s.kuvaus: String, s.myontaja: String, s.vuosi: Int, s.tyyppi, s.index, s.source: String).identify(s.id)
                updatedSuoritukset.put(s.id, Promise[Unit])


            } else {
              updatedSuoritukset.remove(suoritus.id)
            }
          sentSuoritukset.success(())
        }

        override def receive: Actor.Receive = {
          case suoritukset: Seq[Suoritus with Identified[UUID]] =>
            handleSuoritukset(suoritukset)

          case opiskelijat: Seq[Opiskelija with Identified[UUID]] =>
            for (o <- opiskelijat) if (o.henkiloOid != newOid) {
              opiskelijaRekisteri ! Opiskelija(o.oppilaitosOid, o.luokkataso, o.luokka, newOid, o.alkuPaiva, o.loppuPaiva, o.source).identify(o.id)
              updatedOpiskelijat.put(o.id, Promise[Unit])
            } else {
              updatedOpiskelijat.remove(o.id)
            }
            sentOpiskelijat.success(())

          case s:Suoritus with Identified[UUID] => updatedSuoritukset.getOrElse(s.id, Promise[Unit]).success(())
          case o:Opiskelija with Identified[UUID] => updatedOpiskelijat.getOrElse(o.id, Promise[Unit]).success(())
        }

        override def preStart() {
          done.onSuccess{case Updated(opiskelijat, suoritukset) =>
            log.info(s"Updated $opiskelijat opiskelijatTieto rows and $suoritukset suoritus rows")
            retry.cancel()
            context.stop(self)
          }
          done.onFailure{case e:Exception =>
            log.error("problem updating duplicates, trying again", e)
            askForDuplicates
          }

          askForDuplicates

        }



        def askForDuplicates {
          suoritusRekisteri ! SuoritusQuery(Some(henkiloOid), None, None, None)
          opiskelijaRekisteri ! OpiskelijaQuery(Some(henkiloOid), None, None, None, None, None)
        }
      }))





    }



  }


}

case class Deduplicate(url:URL)

case class DeduplicatorResult(henkiloOid: String, masterOid: String)

case class Updated(opiskelijat:Int, suoritukset:Int)

