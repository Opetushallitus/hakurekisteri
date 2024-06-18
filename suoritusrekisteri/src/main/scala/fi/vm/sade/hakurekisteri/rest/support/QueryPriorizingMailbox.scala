package fi.vm.sade.hakurekisteri.rest.support

import akka.actor.ActorSystem
import akka.dispatch.{PriorityGenerator, UnboundedPriorityMailbox}
import com.typesafe.config.Config

class QueryPriorizingMailbox(settings: ActorSystem.Settings, config: Config)
    extends UnboundedPriorityMailbox(
      PriorityGenerator {
        case q: Query[_] => 0
        case _           => 1
      }
    )
