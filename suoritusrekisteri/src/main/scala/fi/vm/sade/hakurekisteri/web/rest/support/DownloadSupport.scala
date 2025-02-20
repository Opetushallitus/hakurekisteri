package fi.vm.sade.hakurekisteri.web.rest.support

import jakarta.servlet.http.HttpServletResponse

import ApiFormat._
import org.scalatra.{CookieOptions, Cookie}
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack

trait DownloadSupport { this: HakuJaValintarekisteriStack =>

  def getFileExtension(t: ApiFormat): String = t match {
    case ApiFormat.Json  => "json"
    case ApiFormat.Xml   => "xml"
    case ApiFormat.Excel => "xlsx"
  }

  def setContentDisposition(t: ApiFormat, response: HttpServletResponse, filename: String) {
    response.setHeader(
      "Content-Disposition",
      s"attachment;filename=$filename.${getFileExtension(t)}"
    )
    response.addCookie(Cookie("fileDownload", "true")(CookieOptions(path = "/")))
  }

}
