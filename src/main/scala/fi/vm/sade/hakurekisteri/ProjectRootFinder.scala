package fi.vm.sade.hakurekisteri

import java.io.File

object ProjectRootFinder {
  def findProjectRoot() = findRoot((new File(".")).getCanonicalFile());

  private def findRoot(currentDirectory: File): File = {
    if (pomExists(currentDirectory) && !parentPomExists(currentDirectory))
      currentDirectory
    else
      findRoot(currentDirectory.getParentFile());
  }

  private def parentPomExists(currentDirectory: File): Boolean = {
    val parent = currentDirectory.getParentFile()
    if (parent == null)
      false
    else
      pomExists(parent) || parentPomExists(parent)
  }

  private def pomExists(currentDirectory: File) = {
    List("pom.xml", "build.sbt", "build.gradle", "build.xml")
      .map(new File(currentDirectory, _))
      .find(_.exists())
      .isDefined
  }
}
