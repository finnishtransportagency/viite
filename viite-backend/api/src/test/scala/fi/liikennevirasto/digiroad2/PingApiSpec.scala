package fi.liikennevirasto.digiroad2

import org.scalatest.funsuite.AnyFunSuite
import org.scalatra.test.scalatest.ScalatraSuite

class PingApiSpec extends AnyFunSuite with ScalatraSuite {
  override def header = response.header // IDE nagged about unimplemented variable
  addServlet(classOf[PingApi], "/ping/*")

  test("Test get() When issuing a ping request Then return status code 200.") {
    get("/ping") {
      response.status should be (200)
    }
  }
}
