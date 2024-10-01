package siirto

import java.nio.charset.Charset
import org.scalatra.ScalatraServlet
import org.springframework.util.StreamUtils

class ValidatorJavascriptServlet extends ScalatraServlet {
  lazy val js = StreamUtils.copyToString(
    getClass.getResourceAsStream("/hakurekisteri-validator.min.js"),
    Charset.forName("utf-8")
  );

  get("/hakurekisteri-validator.min.js") {
    contentType = "application/javascript";
    js
  }
}
