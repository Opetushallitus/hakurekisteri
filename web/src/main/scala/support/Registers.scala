package support

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.camel.CamelExtension
import akka.routing.BroadcastGroup
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.{Oids, Config}
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, ArvosanaActor}
import fi.vm.sade.hakurekisteri.batchimport.{ImportBatch, ImportBatchActor, ImportBatchTable}
import fi.vm.sade.hakurekisteri.integration.audit.AuditUri
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaActor}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusActor}
import fi.vm.sade.hakurekisteri.organization.{FutureOrganizationHierarchy, OrganizationHierarchy}
import fi.vm.sade.hakurekisteri.rest.support.{JDBCJournal, Registers, Resource}
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusActor, VapaamuotoinenSuoritus, VirallinenSuoritus}
import org.apache.activemq.camel.component.ActiveMQComponent
import org.joda.time.LocalDate
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

class BareRegisters(system: ActorSystem, journals: Journals) extends Registers {
  override val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(journals.suoritusJournal)), "suoritukset")
  override val opiskelijaRekisteri = system.actorOf(Props(new OpiskelijaActor(journals.opiskelijaJournal)), "opiskelijat")
  override val opiskeluoikeusRekisteri = system.actorOf(Props(new OpiskeluoikeusActor(journals.opiskeluoikeusJournal)), "opiskeluoikeudet")
  override val arvosanaRekisteri = system.actorOf(Props(new ArvosanaActor(journals.arvosanaJournal)), "arvosanat")
  override val eraRekisteri: ActorRef = system.actorOf(Props(new ImportBatchActor(journals.eraJournal.asInstanceOf[JDBCJournal[ImportBatch, UUID, ImportBatchTable]], 5)), "erat")
}

class AuthorizedRegisters(unauthorized: Registers, system: ActorSystem, config: Config) extends Registers {
  import akka.pattern.ask

  import scala.reflect.runtime.universe._
  implicit val ec:ExecutionContext = system.dispatcher

  def authorizer[A <: Resource[I, A] : ClassTag: Manifest, I: Manifest](guarded: ActorRef, orgFinder: A => Option[String]): ActorRef = {
    val resource = typeOf[A].typeSymbol.name.toString.toLowerCase
    system.actorOf(Props(new OrganizationHierarchy[A, I](guarded, (i: A) => orgFinder(i).map(Set(_)).getOrElse(Set()), config)), s"$resource-authorizer")
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

  val resolve = (arvosana:Arvosana) =>
    unauthorized.suoritusRekisteri.?(arvosana.suoritus)(Timeout(300, TimeUnit.SECONDS)).
      mapTo[Option[Suoritus]].map(
        _.map{
          case (s: VirallinenSuoritus) => Set(s.myontaja, s.source, arvosana.source)
          case (s: VapaamuotoinenSuoritus) => Set(s.source, arvosana.source)
        }.getOrElse(Set()))

  override val suoritusRekisteri = authorizer[Suoritus, UUID](unauthorized.suoritusRekisteri, suoritusOrgResolver.lift)
  override val opiskelijaRekisteri = authorizer[Opiskelija, UUID](unauthorized.opiskelijaRekisteri, (opiskelija) => Some(opiskelija.oppilaitosOid))
  override val opiskeluoikeusRekisteri = authorizer[Opiskeluoikeus, UUID](unauthorized.opiskeluoikeusRekisteri, (opiskeluoikeus) => Some(opiskeluoikeus.myontaja))
  override val arvosanaRekisteri =  system.actorOf(Props(new FutureOrganizationHierarchy[Arvosana, UUID](unauthorized.arvosanaRekisteri, resolve, config)), "arvosana-authorizer")
  override val eraRekisteri: ActorRef = authorizer[ImportBatch, UUID](unauthorized.eraRekisteri, (era) => Some(Oids.ophOrganisaatioOid))
}

class AuditedRegisters(amqUrl: String, amqQueue: String, authorizedRegisters: Registers, system: ActorSystem) extends Registers {
  val log = LoggerFactory.getLogger(getClass)

  //audit
  val camel = CamelExtension(system)
  val broker = "activemq"
  camel.context.addComponent(broker, ActiveMQComponent.activeMQComponent(amqUrl))
  implicit val audit = AuditUri(broker, amqQueue)

  log.debug(s"AuditLog using uri: $amqUrl")

  val suoritusRekisteri = getBroadcastForLogger[Suoritus, UUID](authorizedRegisters.suoritusRekisteri)
  val opiskelijaRekisteri = getBroadcastForLogger[Opiskelija, UUID](authorizedRegisters.opiskelijaRekisteri)
  val opiskeluoikeusRekisteri = getBroadcastForLogger[Opiskeluoikeus, UUID](authorizedRegisters.opiskeluoikeusRekisteri)
  val arvosanaRekisteri = getBroadcastForLogger[Arvosana, UUID](authorizedRegisters.arvosanaRekisteri)

  import fi.vm.sade.hakurekisteri.integration.audit.AuditLog

  import scala.reflect.runtime.universe._

  def getBroadcastForLogger[A <: Resource[I, A]: TypeTag: ClassTag, I: TypeTag: ClassTag](rekisteri: ActorRef) = {
    val routees = List(rekisteri, system.actorOf(Props(new AuditLog[A, I](typeOf[A].typeSymbol.name.toString)).withDispatcher("akka.hakurekisteri.audit-dispatcher"), typeOf[A].typeSymbol.name.toString.toLowerCase + "-audit"))
    system.actorOf(Props.empty.withRouter(BroadcastGroup(paths = routees map (_.path.toString))))
  }

  override val eraRekisteri: ActorRef = system.deadLetters
}