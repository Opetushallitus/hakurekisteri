package fi.vm.sade.hakurekisteri.acceptance.tools

import akka.actor.{ActorRef, Actor}
import fi.vm.sade.hakurekisteri.rest.support.Query
import fi.vm.sade.hakurekisteri.organization.{AuthorizedRead, AuthorizedQuery}


class FakeAuthorizer(guarded:ActorRef) extends Actor {
  override def receive: FakeAuthorizer#Receive =  {
    case AuthorizedQuery(q,orgs, _) => guarded forward q
    case AuthorizedRead(id, orgs, _) => guarded forward id
    case message:AnyRef => guarded forward message
  }
}
