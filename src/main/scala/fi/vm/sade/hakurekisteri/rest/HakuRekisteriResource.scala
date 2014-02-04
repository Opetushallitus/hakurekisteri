package fi.vm.sade.hakurekisteri.rest

import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import org.scalatra.swagger.SwaggerSupport
import org.scalatra.json.{JacksonJsonSupport, JacksonJsonOutput}


abstract class HakurekisteriResource extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with SwaggerSupport {


}
