package fi.vm.sade.hakurekisteri

import java.io.File

import org.apache.catalina.startup.Tomcat
import org.apache.commons.io.FileUtils

object HakuRekisteriTomcat extends App {
  val port = 8080
  val projectRoot = ProjectRootFinder.findProjectRoot()
  val webappRoot = projectRoot + "/web/src/main/webapp/"
  val contextPath = "/"

  val tomcat = new Tomcat() {
    override def start() {
      super.start();
      Runtime.getRuntime().addShutdownHook(new Thread("Tomcat work directory delete hook") {
        override def run() {
          FileUtils.deleteDirectory(new File(basedir));
        }
      });
    }
  };
  this.tomcat.setPort(port);


  val webContext = tomcat.addWebapp(contextPath, webappRoot);
  tomcat.addWebapp("/compiled", projectRoot + "/web/target/javascript/compiled/")

  tomcat.start();
  if(!webContext.getState().isAvailable()) {
    this.tomcat.stop();
    this.tomcat.getServer().await();
    throw new RuntimeException("Tomcat context failed to start");
  }
}

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
    List("pom.xml", "build.sbt", "build.gradle", "build.xml").map(new File(currentDirectory, _)).find(_.exists()).isDefined
  }
}