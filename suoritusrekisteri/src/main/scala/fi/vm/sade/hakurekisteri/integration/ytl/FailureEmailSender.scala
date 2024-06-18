package fi.vm.sade.hakurekisteri.integration.ytl

import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.valpas.SlowFutureLogger.getClass
import org.slf4j.LoggerFactory

import javax.mail.Message.RecipientType
import javax.mail.Session
import javax.mail.internet.{InternetAddress, MimeMessage}

abstract class FailureEmailSender {
  def sendFailureEmail(txt: String): Unit
}

class RealFailureEmailSender(config: Config) extends FailureEmailSender {
  val logger = LoggerFactory.getLogger(getClass)
  override def sendFailureEmail(txt: String): Unit = {
    val session = Session.getInstance(config.email.getAsJavaProperties())
    val msg = new MimeMessage(session)
    msg.setText(txt)
    msg.setSubject("YTL sync all failed")
    msg.setFrom(new InternetAddress(config.email.smtpSender))
    val tr = session.getTransport("smtp")
    try {
      val recipients: Array[javax.mail.Address] = config.properties
        .getOrElse("suoritusrekisteri.ytl.error.report.recipients", "")
        .split(",")
        .map(address => {
          new InternetAddress(address)
        })
      msg.setRecipients(RecipientType.TO, recipients)
      tr.connect(config.email.smtpHost, config.email.smtpUsername, config.email.smtpPassword)
      msg.saveChanges()
      logger.debug(s"Sending failure email to $recipients (text=$msg)")
      tr.sendMessage(msg, msg.getAllRecipients)
    } catch {
      case e: Throwable =>
        logger.error("Could not send email", e)
    } finally {
      tr.close()
    }
  }
}

class MockFailureEmailSender extends FailureEmailSender {
  val logger = LoggerFactory.getLogger(getClass)
  override def sendFailureEmail(txt: String): Unit = {
    logger.info(s"Sending failure email: $txt")
  }
}
