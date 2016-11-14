package fi.vm.sade.hakurekisteri.integration.ytl

import java.io.{FileOutputStream, FileInputStream, File}
import java.nio.file.{Files, Path, Paths}
import java.text.SimpleDateFormat

import fi.vm.sade.properties.OphProperties
import org.slf4j.LoggerFactory
import scala.collection.JavaConversions._

class YtlFileSystem(config: OphProperties) {
  private val logger = LoggerFactory.getLogger(getClass)

  val directoryPath: File =
    Option(config.getOrElse("ytl.http.download.directory", null))
        .map(new File(_))
      .getOrElse {
        logger.warn("Using OS temporary directory for YTL files since 'ytl.http.download.directory' configuration is missing!")
        com.google.common.io.Files.createTempDir()
      }


  def read(uuid: String): Iterator[FileInputStream] = {
    Files.newDirectoryStream(directoryPath.toPath).iterator()
      .filter(!_.toFile.isDirectory)
      .filter(_.toString.contains(uuid)).map(file => new FileInputStream(file.toFile))
  }

  def write(groupUuid: String, uuid: String): FileOutputStream = {
    val file = Paths.get(directoryPath.getPath(), s"${now()}_${groupUuid}_${uuid}_student-results.zip").toFile
    logger.info(s"Saving file ${file}")
    new FileOutputStream(file)
  }

  private def now() = new SimpleDateFormat("dd-MM-yyyy_HH-mm").format(new java.util.Date())

}
