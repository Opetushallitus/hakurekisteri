package fi.vm.sade.hakurekisteri.integration.ytl

import java.io.{FileOutputStream, FileInputStream, File}
import java.nio.file.{Files, Path, Paths}
import java.text.SimpleDateFormat

import fi.vm.sade.properties.OphProperties
import org.slf4j.LoggerFactory
import scala.collection.JavaConversions._

class YtlFileSystem(config: OphProperties) {
  val logger = LoggerFactory.getLogger(getClass)

  private val directoryPath: Option[String] =
    Some(config.getOrElse("ytl.http.download.directory", ""))
      .filter { p =>
        if(p.isEmpty) {
          logger.warn("Using OS temporary directory for YTL files since 'ytl.http.download.directory' configuration is missing!")
          false
        } else { true }
      }

  private val directory: File =
    directoryPath match {
      case Some(path) => new File(path)
      case None => com.google.common.io.Files.createTempDir()
    }

  def read(uuid: String): FileInputStream = {
    Files.newDirectoryStream(directory.toPath).iterator()
      .filter(!_.toFile.isDirectory)
      .find(_.toString.contains(uuid)) match {
      case Some(file) =>
        new FileInputStream(file.toFile)
      case None =>
        throw new RuntimeException(s"File with uuid(${uuid}) not found in directory ${directory}!")
    }
  }

  def write(uuid: String): FileOutputStream = {
    if(directoryPath.isEmpty) {

    }
    val file = Paths.get(directory.getPath(), s"${now()}_${uuid}_student-results.zip").toFile
    logger.info(s"Saving file ${file}")
    new FileOutputStream(file)
  }


  def now() = new SimpleDateFormat("dd-MM-yyyy_HH-mm").format(new java.util.Date())

}
