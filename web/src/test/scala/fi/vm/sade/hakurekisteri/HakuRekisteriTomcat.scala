package fi.vm.sade.hakurekisteri

import java.io.File

import org.apache.catalina.startup.Tomcat
import org.apache.commons.io.FileUtils

object HakuRekisteriTomcat extends App {
  new HakuRekisteriTomcat(8080).start
}

class HakuRekisteriTomcat(port: Int, profile: String = Config.profile) {
  Config.profile = profile
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

  val webapp = tomcat.addWebapp(contextPath, webappRoot)
  val javascripts = tomcat.addWebapp("/compiled", projectRoot + "/web/target/javascript/compiled/")

  def start: Unit = {
    tomcat.start();
    if(!webapp.getState().isAvailable()) {
      this.tomcat.stop();
      this.tomcat.getServer().await();
      throw new RuntimeException("Tomcat context failed to start");
    }
  }

  def stop: Unit = {
    tomcat.stop()
  }

  def withTomcat[T](block: => T) = {
    start
    try {
      block
    } finally {
      stop
    }
  }
}