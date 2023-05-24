package fi.liikennevirasto.digiroad2

import org.scalatest.FunSuite
import org.scalatra.test.scalatest.ScalatraSuite

class PingApiSpec extends FunSuite with ScalatraSuite {
  addServlet(classOf[PingApi], "/ping/*")

  test("Test get() When issuing a ping request Then return status code 200.") {
    get("/ping") {
      response.status should be (200)
    }
  }
}
