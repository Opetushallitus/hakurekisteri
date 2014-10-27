package fi.vm.sade.hakurekisteri.rest.support

import java.io.OutputStream

import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import scala.reflect.ClassTag


trait ExcelSupport[T] { this: HakuJaValintarekisteriStack with JacksonJsonSupport =>

  implicit val ct: ClassTag[T]

  addMimeMapping("application/octet-stream", "binary")

  val streamingRender: (OutputStream, T) => Unit

  import scala.reflect.classTag


  def renderExcel: RenderPipeline = {
    case hakijat if  classTag[T].runtimeClass.isInstance(hakijat) && format == "binary" =>
      streamingRender(response.outputStream, classTag[T].runtimeClass.cast(hakijat).asInstanceOf[T])
  }

}
