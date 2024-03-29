package fi.vm.sade.hakurekisteri.web.rest.support

import java.io.OutputStream

import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import scala.reflect.ClassTag
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack

trait ExcelSupport[T] { this: HakuJaValintarekisteriStack with JacksonJsonSupport =>

  implicit val ct: ClassTag[T]

  addMimeMapping("application/octet-stream", "binary")

  val streamingRender: (OutputStream, T) => Unit

  import scala.reflect.classTag

  def renderExcel: RenderPipeline = {
    case hakijat if classTag[T].runtimeClass.isInstance(hakijat) && format == "binary" =>
      streamingRender(response.outputStream, classTag[T].runtimeClass.cast(hakijat).asInstanceOf[T])
  }

}
