package fi.vm.sade.hakurekisteri.rest.support

import javax.servlet.http.HttpServletResponse

import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.rest.support.ApiFormat._
import org.scalatra.{CookieOptions, Cookie}

trait DownloadSupport { this: HakuJaValintarekisteriStack =>

  def getFileExtension(t: ApiFormat): String = t match {
    case ApiFormat.Json => "json"
    case ApiFormat.Xml => "xml"
    case ApiFormat.Excel => "xls"
  }

  def setContentDisposition(t: ApiFormat, response: HttpServletResponse, filename: String) {
    response.setHeader("Content-Disposition", s"attachment;filename=$filename.${getFileExtension(t)}")
    response.addCookie(Cookie("fileDownload", "true")(CookieOptions(path = "/")))
  }

}
