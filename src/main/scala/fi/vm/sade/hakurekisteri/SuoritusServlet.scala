package fi.vm.sade.hakurekisteri

import org.scalatra._
import scalate.ScalateSupport

class SuoritusServlet extends HakuJaValintarekisteriStack {

  get("/") {
    <html>
      <body>
        <h1>Hello, world!</h1>
        Say <a href="hello-scalate">hello to Scalate</a>.
      </body>
    </html>
  }
  
}
