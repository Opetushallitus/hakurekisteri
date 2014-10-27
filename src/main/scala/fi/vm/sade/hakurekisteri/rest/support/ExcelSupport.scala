package fi.vm.sade.hakurekisteri.rest.support

import java.io.OutputStream

import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport


trait ExcelSupport[T] { this: HakuJaValintarekisteriStack with JacksonJsonSupport =>

  addMimeMapping("application/octet-stream", "binary")

  val streamingRender: (OutputStream, T) => Unit

  def renderExcel: RenderPipeline = {
    case hakijat: T if format == "binary" => streamingRender(response.outputStream, hakijat)
  }

}
