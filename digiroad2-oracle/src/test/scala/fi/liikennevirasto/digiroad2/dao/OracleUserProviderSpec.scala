package fi.liikennevirasto.digiroad2.dao

import fi.liikennevirasto.digiroad2.user.Configuration
import fi.liikennevirasto.digiroad2.util.SqlScriptRunner._
import org.scalatest.{FunSuite, Matchers}

class OracleUserProviderSpec extends FunSuite with Matchers {
  val TestUserName = "Oracleuserprovidertest"
  val north = 1000

  val provider = new OracleUserProvider

  test("Test OracleUserProvider.getUser() and OracleUserProvider.createUser() Whe trying to find a specific user name and creating a user for that user name Then getUser() should return None before creating and the user after creating it.") {
    executeStatement("DELETE FROM service_user WHERE username = '" + TestUserName.toLowerCase() + "'");
    provider.getUser(TestUserName) shouldBe (None)
    provider.createUser(TestUserName, Configuration(north = Some(1000)))
    val user = provider.getUser(TestUserName).get
    user.username should be (TestUserName.toLowerCase)
    user.configuration.north should be (Some(north))
  }
}