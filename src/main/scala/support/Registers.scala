package support

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.{Config, Oids}
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, ArvosanaActor, ArvosanaJDBCActor, ArvosanaTable}
import fi.vm.sade.hakurekisteri.batchimport.{ImportBatch, ImportBatchActor, ImportBatchTable}
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaActor}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusActor}
import fi.vm.sade.hakurekisteri.organization.{FutureOrganizationHierarchy, OrganizationHierarchy}
import fi.vm.sade.hakurekisteri.rest.support.{JDBCJournal, Registers, Resource}
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusActor, VapaamuotoinenSuoritus, VirallinenSuoritus}
import org.joda.time.LocalDate

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

class BareRegisters(system: ActorSystem, journals: Journals) extends Registers {
  override val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(journals.suoritusJournal)), "suoritukset")
  override val opiskelijaRekisteri = system.actorOf(Props(new OpiskelijaActor(journals.opiskelijaJournal)), "opiskelijat")
  override val opiskeluoikeusRekisteri = system.actorOf(Props(new OpiskeluoikeusActor(journals.opiskeluoikeusJournal)), "opiskeluoikeudet")
  override val arvosanaRekisteri = system.actorOf(Props(new ArvosanaJDBCActor(journals.arvosanaJournal.asInstanceOf[JDBCJournal[Arvosana, UUID, ArvosanaTable]], 5)), "arvosanat")
  override val eraRekisteri: ActorRef = system.actorOf(Props(new ImportBatchActor(journals.eraJournal.asInstanceOf[JDBCJournal[ImportBatch, UUID, ImportBatchTable]], 5)), "erat")
}

class AuthorizedRegisters(unauthorized: Registers, system: ActorSystem, config: Config) extends Registers {
  import akka.pattern.ask

  import scala.reflect.runtime.universe._
  implicit val ec:ExecutionContext = system.dispatcher

  def authorizer[A <: Resource[I, A] : ClassTag: Manifest, I: Manifest](guarded: ActorRef, orgFinder: A => Option[String], komoFinder: A => Option[String]): ActorRef = {
    val resource = typeOf[A].typeSymbol.name.toString.toLowerCase
    system.actorOf(Props(new OrganizationHierarchy[A, I](guarded, (i: A) => (orgFinder(i).map(Set(_)).getOrElse(Set()), komoFinder(i)), config)), s"$resource-authorizer")
  }

  def authorizer[A <: Resource[I, A] : ClassTag: Manifest, I: Manifest](guarded: ActorRef, orgFinder: A => Option[String]): ActorRef = {
    val resource = typeOf[A].typeSymbol.name.toString.toLowerCase
    system.actorOf(Props(new OrganizationHierarchy[A, I](guarded, (i: A) => (orgFinder(i).map(Set(_)).getOrElse(Set()), None), config)), s"$resource-authorizer")
  }

  val suoritusOrgResolver: PartialFunction[Suoritus, String] = {
    case VirallinenSuoritus(komo: String,
    myontaja: String,
    tila: String,
    valmistuminen: LocalDate,
    henkilo: String,
    yksilollistaminen,
    suoritusKieli: String,
    opiskeluoikeus: Option[UUID],
    vahv: Boolean,
    lahde: String) => myontaja
  }

  val suoritusKomoResolver: PartialFunction[Suoritus, String] = {
    case VirallinenSuoritus(komo: String,
    myontaja: String,
    tila: String,
    valmistuminen: LocalDate,
    henkilo: String,
    yksilollistaminen,
    suoritusKieli: String,
    opiskeluoikeus: Option[UUID],
    vahv: Boolean,
    lahde: String) => komo
  }

  val resolve = (arvosana:Arvosana) =>
    unauthorized.suoritusRekisteri.?(arvosana.suoritus)(Timeout(300, TimeUnit.SECONDS)).
      mapTo[Option[Suoritus]].map(
      _.map {
        case (s: VirallinenSuoritus) => Set(s.myontaja, s.source, arvosana.source)
        case (s: VapaamuotoinenSuoritus) => Set(s.source, arvosana.source)
      }.getOrElse(Set())).zip(Future(None))

  override val suoritusRekisteri = authorizer[Suoritus, UUID](unauthorized.suoritusRekisteri, suoritusOrgResolver.lift, suoritusKomoResolver.lift)
  override val opiskelijaRekisteri = authorizer[Opiskelija, UUID](unauthorized.opiskelijaRekisteri, (opiskelija:Opiskelija) => Some(opiskelija.oppilaitosOid))
  override val opiskeluoikeusRekisteri = authorizer[Opiskeluoikeus, UUID](unauthorized.opiskeluoikeusRekisteri, (opiskeluoikeus:Opiskeluoikeus) => Some(opiskeluoikeus.myontaja), (opiskeluoikeus:Opiskeluoikeus) => Some(opiskeluoikeus.komo))
  override val arvosanaRekisteri =  system.actorOf(Props(new FutureOrganizationHierarchy[Arvosana, UUID](unauthorized.arvosanaRekisteri, resolve, config)), "arvosana-authorizer")
  override val eraRekisteri: ActorRef = authorizer[ImportBatch, UUID](unauthorized.eraRekisteri, (era:ImportBatch) => Some(Oids.ophOrganisaatioOid))
}