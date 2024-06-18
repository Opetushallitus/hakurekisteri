package fi.vm.sade.hakurekisteri.test.tools

import org.apache.commons.io.IOUtils
import org.springframework.core.io.ClassPathResource
import scala.collection.JavaConverters._

object ClassPathUtil {
  def readFileFromClasspath(usingClass: Class[_], fileNameInSamePackage: String): String = {
    val classPathResource = new ClassPathResource(fileNameInSamePackage, usingClass)
    IOUtils.readLines(classPathResource.getInputStream, "UTF-8").asScala.mkString
  }
}
