package fi.vm.sade.hakurekisteri

import java.util.UUID

import akka.event.Logging
import fi.vm.sade.hakurekisteri.integration.ExecutorUtil
import fi.vm.sade.hakurekisteri.rest.support.Query
import fi.vm.sade.hakurekisteri.storage.repository._
import fi.vm.sade.hakurekisteri.storage.{Identified, InMemQueryingResourceService, ResourceActor}

import scala.concurrent.ExecutionContext



class TestActor(val journal: Journal[TestResource, UUID]) extends ResourceActor[TestResource, UUID]  with JournaledRepository[TestResource, UUID] with InMemQueryingResourceService[TestResource ,UUID] {
  override implicit val executionContext: ExecutionContext = ExecutorUtil.createExecutor(8, getClass.getSimpleName)

  def this() = this(new InMemJournal[TestResource, UUID])

  override val logger = Logging(context.system, this)


  override val matcher: PartialFunction[Query[TestResource], (TestResource with Identified[UUID]) => Boolean] = {
    case _ => (_) => true
  }
}
