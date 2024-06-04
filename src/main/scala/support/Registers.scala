package support

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, ArvosanaJDBCActor, ArvosanatQuery}
import fi.vm.sade.hakurekisteri.batchimport.{ImportBatch, ImportBatchActor, ImportBatchOrgActor}
import fi.vm.sade.hakurekisteri.integration.hakemus.HakemusBasedPermissionCheckerActorRef
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.integration.{ExecutorUtil, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaJDBCActor}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusJDBCActor}
import fi.vm.sade.hakurekisteri.organization.{
  AuthorizationSubject,
  AuthorizationSubjectFinder,
  FutureOrganizationHierarchy,
  OrganizationHierarchy
}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.{Registers, Resource}
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus._
import fi.vm.sade.hakurekisteri.{Config, KomoOids, Oids}

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
class BareRegisters(
  system: ActorSystem,
  journals: Journals,
  db: Database,
  integrationsProvider: PersonAliasesProvider,
  config: Config
) extends Registers {
  override val suoritusRekisteri = system.actorOf(
    Props(new SuoritusJDBCActor(journals.suoritusJournal, 15, integrationsProvider, config)),
    "suoritukset"
  )
  override val ytlSuoritusRekisteri = system.actorOf(
    Props(new SuoritusJDBCActor(journals.suoritusJournal, 5, integrationsProvider, config)),
    "ytl-suoritukset"
  )
  override val opiskelijaRekisteri = system.actorOf(
    Props(new OpiskelijaJDBCActor(journals.opiskelijaJournal, 10, config)),
    "opiskelijat"
  )
  override val opiskeluoikeusRekisteri = system.actorOf(
    Props(new OpiskeluoikeusJDBCActor(journals.opiskeluoikeusJournal, 5, config)),
    "opiskeluoikeudet"
  )
  override val arvosanaRekisteri =
    system.actorOf(Props(new ArvosanaJDBCActor(journals.arvosanaJournal, 5, config)), "arvosanat")
  override val ytlArvosanaRekisteri = system.actorOf(
    Props(new ArvosanaJDBCActor(journals.arvosanaJournal, 5, config)),
    "ytl-arvosanat"
  )
  override val eraRekisteri: ActorRef =
    system.actorOf(Props(new ImportBatchActor(journals.eraJournal, 5, config)), "erat")
  override val eraOrgRekisteri: ActorRef =
    system.actorOf(Props(new ImportBatchOrgActor(db, config)), "era-orgs")
}

class AuthorizedRegisters(
  unauthorized: Registers,
  system: ActorSystem,
  config: Config,
  hakemusBasedPermissionCheckerActor: HakemusBasedPermissionCheckerActorRef
) extends Registers {
  import akka.pattern.ask

  import scala.reflect.runtime.universe._
  implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(
    config.integrations.asyncOperationThreadPoolSize,
    getClass.getSimpleName
  )

  val orgRestExecutor = ExecutorUtil.createExecutor(5, "authorizer-organization-rest-client-pool")
  val organisaatioClient: VirkailijaRestClient =
    new VirkailijaRestClient(config.integrations.organisaatioConfig, None)(orgRestExecutor, system)

  def authorizer[A <: Resource[I, A]: ClassTag: Manifest, I: Manifest](
    guarded: ActorRef,
    authFinder: A => AuthorizationSubject[A]
  ): ActorRef = {
    val resource = typeOf[A].typeSymbol.name.toString.toLowerCase
    val organizationFinder = (items: Seq[A]) => items.map(authFinder)
    system.actorOf(
      Props(
        new OrganizationHierarchy[A, I](
          guarded,
          organizationFinder,
          config,
          organisaatioClient,
          hakemusBasedPermissionCheckerActor
        )
      ),
      s"$resource-authorizer"
    )
  }

  private val suoritusResolver: AuthorizationSubjectFinder[Suoritus] =
    new AuthorizationSubjectFinder[Suoritus] {
      override def apply(
        suoritukset: Seq[Suoritus]
      ): Future[Seq[AuthorizationSubject[Suoritus]]] = {
        val kielikoesuoritusIdt = suoritukset.collect {
          case s: VirallinenSuoritus with Identified[_]
              if s.komo == KomoOids.ammatillisenKielikoe =>
            s.id.asInstanceOf[UUID]
        }.toSet
        val kielikoesuoritustenMyontajat = if (kielikoesuoritusIdt.isEmpty) {
          Future.successful(Map.empty[UUID, Set[String]])
        } else {
          unauthorized.arvosanaRekisteri
            .?(ArvosanatQuery(kielikoesuoritusIdt))(Timeout(900, TimeUnit.SECONDS))
            .mapTo[Seq[Arvosana]]
            .map(arvosanat => arvosanat.groupBy(_.suoritus).mapValues(_.map(_.source).toSet))
        }
        kielikoesuoritustenMyontajat.map(ksm =>
          suoritukset.map {
            case s: VirallinenSuoritus with Identified[_] =>
              AuthorizationSubject(
                s.asInstanceOf[Suoritus],
                ksm.getOrElse(s.id.asInstanceOf[UUID], Set(s.myontaja)),
                personOid = Some(s.henkiloOid),
                komo = Some(s.komo)
              )
            case s: VirallinenSuoritus =>
              AuthorizationSubject(
                s.asInstanceOf[Suoritus],
                Set(s.myontaja),
                personOid = Some(s.henkiloOid),
                komo = Some(s.komo)
              )
            case s =>
              AuthorizationSubject(
                s,
                Set.empty[String],
                personOid = Some(s.henkiloOid),
                komo = None
              )
          }
        )
      }
    }

  private val arvosanaResolver: AuthorizationSubjectFinder[Arvosana] =
    new AuthorizationSubjectFinder[Arvosana] {
      override def apply(arvosanat: Seq[Arvosana]): Future[Seq[AuthorizationSubject[Arvosana]]] = {
        unauthorized.suoritusRekisteri
          .?(arvosanat.map(_.suoritus))(Timeout(900, TimeUnit.SECONDS))
          .mapTo[Seq[Suoritus with Identified[UUID]]]
          .map(suoritukset => {
            val suorituksetM = suoritukset.map(s => (s.id, s.asInstanceOf[Suoritus])).toMap
            arvosanat.map(a =>
              suorituksetM(a.suoritus) match {
                case s: VirallinenSuoritus if s.komo == KomoOids.ammatillisenKielikoe =>
                  AuthorizationSubject(
                    a,
                    Set(a.source),
                    personOid = Some(s.henkiloOid),
                    komo = None
                  )
                case s: VirallinenSuoritus =>
                  AuthorizationSubject(
                    a,
                    Set(s.myontaja, s.source),
                    personOid = Some(s.henkiloOid),
                    komo = None
                  )
                case s: VapaamuotoinenSuoritus =>
                  AuthorizationSubject(
                    a,
                    Set(s.source),
                    personOid = Some(s.henkiloOid),
                    komo = None
                  )
              }
            )
          })
      }
    }

  override val suoritusRekisteri: ActorRef = {
    system.actorOf(
      Props(
        new FutureOrganizationHierarchy[Suoritus, UUID](
          unauthorized.suoritusRekisteri,
          suoritusResolver,
          config,
          organisaatioClient,
          hakemusBasedPermissionCheckerActor
        )
      ),
      "suoritus-authorizer"
    )
  }

  override val opiskelijaRekisteri: ActorRef = {
    authorizer[Opiskelija, UUID](
      unauthorized.opiskelijaRekisteri,
      AuthorizedRegisters.opiskelijaAuthFinder
    )
  }

  override val opiskeluoikeusRekisteri: ActorRef = {
    authorizer[Opiskeluoikeus, UUID](
      unauthorized.opiskeluoikeusRekisteri,
      AuthorizedRegisters.opiskeluoikeusAuthFinder
    )
  }

  override val arvosanaRekisteri: ActorRef = {
    system.actorOf(
      Props(
        new FutureOrganizationHierarchy[Arvosana, UUID](
          unauthorized.arvosanaRekisteri,
          arvosanaResolver,
          config,
          organisaatioClient,
          hakemusBasedPermissionCheckerActor
        )
      ),
      "arvosana-authorizer"
    )
  }

  override val eraRekisteri: ActorRef = {
    authorizer[ImportBatch, UUID](unauthorized.eraRekisteri, AuthorizedRegisters.eraAuthFinder)
  }

  override val eraOrgRekisteri: ActorRef = unauthorized.eraOrgRekisteri
  override val ytlSuoritusRekisteri: ActorRef = null
  override val ytlArvosanaRekisteri: ActorRef = null
}

object AuthorizedRegisters {
  val opiskelijaAuthFinder = { opiskelija: Opiskelija =>
    AuthorizationSubject(
      opiskelija,
      Set(opiskelija.oppilaitosOid),
      personOid = Some(opiskelija.henkiloOid),
      komo = None
    )
  }

  val opiskeluoikeusAuthFinder: Opiskeluoikeus => AuthorizationSubject[Opiskeluoikeus] = {
    opiskeluoikeus: Opiskeluoikeus =>
      AuthorizationSubject(
        opiskeluoikeus,
        Set(opiskeluoikeus.myontaja),
        personOid = Some(opiskeluoikeus.henkiloOid),
        komo = Some(opiskeluoikeus.komo)
      )
  }

  val eraAuthFinder: ImportBatch => AuthorizationSubject[ImportBatch] = { era: ImportBatch =>
    AuthorizationSubject(era, Set(Oids.ophOrganisaatioOid), personOid = None, komo = None)
  }
}

trait PersonAliasesProvider {
  def enrichWithAliases(henkiloOids: Set[String]): Future[PersonOidsWithAliases]
}
