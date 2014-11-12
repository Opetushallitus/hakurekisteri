package org.scalatra.commands

import java.util.UUID
import javax.servlet.http.HttpServletRequest

import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriCommand, Resource}
import org.scalatra.json.{JacksonJsonSupport, JacksonJsonValueReaderProperty}


trait HakurekisteriParsing[A <: Resource[UUID, A]] extends CommandSupport with JacksonJsonValueReaderProperty { self: JacksonJsonSupport with CommandSupport =>
  type CommandType = HakurekisteriCommand[A]


  override protected def bindCommand[T <: CommandType](newCommand: T)(implicit request: HttpServletRequest, mf: Manifest[T]): T = {
    val requestFormat = request.contentType match {
      case Some(contentType) => mimeTypes.getOrElse(contentType, format)
      case None => format
    }

    requestFormat match {
      case "json" | "xml" => newCommand.bindTo(parsedBody(request), multiParams(request), request.headers)
      case _ => newCommand.bindTo(params(request), multiParams(request), request.headers)
    }
    request.update(commandRequestKey[T], newCommand)
    newCommand
  }

}
