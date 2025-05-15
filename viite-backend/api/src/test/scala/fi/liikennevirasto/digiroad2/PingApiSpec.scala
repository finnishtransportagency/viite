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

  test("Ping API should return OK and database time in correct format") {
    get("/ping") {
      body should include("OK")
      body should include("DB Time:")
      body should fullyMatch regex """OK \(DB Time: \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\)\n"""
    }
  }
}
