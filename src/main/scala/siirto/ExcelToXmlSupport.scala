package siirto

import fi.vm.sade.hakurekisteri.rest.support.LocalDateSerializer
import org.joda.time.format.{DateTimeFormat, ISODateTimeFormat}
import org.scalatra.servlet.FileItem

trait ExcelToXmlSupport {
  private val excelContentTypes = Set(
    "application/vnd.ms-excel",
    "application/msexcel",
    "application/x-msexcel",
    "application/x-ms-excel",
    "application/x-excel",
    "application/x-dos_ms_excel",
    "application/xls",
    "application/x-xls",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
  )
  private val XmlDate = "[0-9]{4}-[0-9]{2}-[0-9]{2}".r

  def isExcel(f: FileItem): Boolean =
    f.name.toLowerCase.endsWith(".xls") || f.name.toLowerCase.endsWith(".xlsx") || f.getContentType
      .exists(excelContentTypes.contains)

  def toXmlDate(value: String): String = value match {
    case XmlDate() => value
    case finDate =>
      ISODateTimeFormat
        .yearMonthDay()
        .print(DateTimeFormat.forPattern(LocalDateSerializer.dayFormat).parseDateTime(finDate))
  }
}
