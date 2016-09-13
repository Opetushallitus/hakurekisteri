package support

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, ArvosanaJDBCActor}
import fi.vm.sade.hakurekisteri.batchimport.{ImportBatch, ImportBatchActor}
import fi.vm.sade.hakurekisteri.integration.{ExecutorUtil, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaJDBCActor}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusJDBCActor}
import fi.vm.sade.hakurekisteri.organization.{FutureOrganizationHierarchy, OrganizationHierarchy}
import fi.vm.sade.hakurekisteri.rest.support.{Registers, Resource}
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus._
import fi.vm.sade.hakurekisteri.{Config, Oids}
import org.joda.time.LocalDate

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

class BareRegisters(system: ActorSystem, journals: Journals) extends Registers {
  override val suoritusRekisteri = system.actorOf(Props(new SuoritusJDBCActor(journals.suoritusJournal, 5)), "suoritukset")
  override val ytlSuoritusRekisteri = system.actorOf(Props(new SuoritusJDBCActor(journals.suoritusJournal, 5)), "ytl-suoritukset")
  override val opiskelijaRekisteri = system.actorOf(Props(new OpiskelijaJDBCActor(journals.opiskelijaJournal, 5)), "opiskelijat")
  override val opiskeluoikeusRekisteri = system.actorOf(Props(new OpiskeluoikeusJDBCActor(journals.opiskeluoikeusJournal, 5)), "opiskeluoikeudet")
  override val arvosanaRekisteri = system.actorOf(Props(new ArvosanaJDBCActor(journals.arvosanaJournal, 5)), "arvosanat")
  override val ytlArvosanaRekisteri = system.actorOf(Props(new ArvosanaJDBCActor(journals.arvosanaJournal, 5)), "ytl-arvosanat")
  override val eraRekisteri: ActorRef = system.actorOf(Props(new ImportBatchActor(journals.eraJournal, 5)), "erat")
}

class AuthorizedRegisters(unauthorized: Registers, system: ActorSystem, config: Config) extends Registers {
  import akka.pattern.ask

  import scala.reflect.runtime.universe._
  implicit val ec:ExecutionContext = system.dispatcher

  val orgRestExecutor = ExecutorUtil.createExecutor(5, "authorizer-organization-rest-client-pool")
  val organisaatioClient: VirkailijaRestClient = new VirkailijaRestClient(config.integrations.organisaatioConfig, None)(orgRestExecutor, system)

  def authorizer[A <: Resource[I, A] : ClassTag: Manifest, I: Manifest](guarded: ActorRef, orgFinder: A => Option[String], komoFinder: A => Option[String]): ActorRef = {
    val resource = typeOf[A].typeSymbol.name.toString.toLowerCase
    system.actorOf(Props(new OrganizationHierarchy[A, I](guarded, (is: Seq[A]) => is.map(i => (i, orgFinder(i).map(Set(_)).getOrElse(Set()), komoFinder(i))), config, organisaatioClient)), s"$resource-authorizer")
  }

  def authorizer[A <: Resource[I, A] : ClassTag: Manifest, I: Manifest](guarded: ActorRef, orgFinder: A => Option[String]): ActorRef = {
    val resource = typeOf[A].typeSymbol.name.toString.toLowerCase
    system.actorOf(Props(new OrganizationHierarchy[A, I](guarded, (is: Seq[A]) => is.map(i => (i, orgFinder(i).map(Set(_)).getOrElse(Set()), None)), config, organisaatioClient)), s"$resource-authorizer")
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

  val resolve = (arvosanat: Seq[Arvosana]) =>
      unauthorized.suoritusRekisteri.?(arvosanat.map(_.suoritus))(Timeout(900, TimeUnit.SECONDS)).
        mapTo[Seq[Suoritus with Identified[UUID]]].map(suoritukset => {
        val suoritusAuthInfo = suoritukset.map(s => (s.id, s.asInstanceOf[Suoritus])).map {
          case (id, s: VirallinenSuoritus) => (id, Set(s.myontaja, s.source))
          case (id, s: VapaamuotoinenSuoritus) => (id, Set(s.source))
          case (id, s: AmmatillisenKielikoeSuoritus) => (id, Set(s.myontaja, s.source))
        }.toMap
        arvosanat.map(x => (x, suoritusAuthInfo(x.suoritus), None))
      })

  override val suoritusRekisteri = authorizer[Suoritus, UUID](unauthorized.suoritusRekisteri, suoritusOrgResolver.lift, suoritusKomoResolver.lift)
  override val opiskelijaRekisteri = authorizer[Opiskelija, UUID](unauthorized.opiskelijaRekisteri, (opiskelija:Opiskelija) => Some(opiskelija.oppilaitosOid))
  override val opiskeluoikeusRekisteri = authorizer[Opiskeluoikeus, UUID](unauthorized.opiskeluoikeusRekisteri, (opiskeluoikeus:Opiskeluoikeus) => Some(opiskeluoikeus.myontaja), (opiskeluoikeus:Opiskeluoikeus) => Some(opiskeluoikeus.komo))
  override val arvosanaRekisteri = system.actorOf(Props(new FutureOrganizationHierarchy[Arvosana, UUID](unauthorized.arvosanaRekisteri, resolve, config, organisaatioClient)), "arvosana-authorizer")
  override val eraRekisteri: ActorRef = authorizer[ImportBatch, UUID](unauthorized.eraRekisteri, (era:ImportBatch) => Some(Oids.ophOrganisaatioOid))
  override val ytlSuoritusRekisteri: ActorRef = null
  override val ytlArvosanaRekisteri: ActorRef = null
}
