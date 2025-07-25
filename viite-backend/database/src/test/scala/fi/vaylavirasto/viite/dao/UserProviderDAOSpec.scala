package fi.vaylavirasto.viite.dao

import fi.liikennevirasto.digiroad2.user.Configuration
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC.runWithRollback
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class UserProviderDAOSpec extends AnyFunSuite with Matchers {

  val TestUserName = "userprovidertest"

  val provider = new UserProviderDAO

  test("Delete, get and add user with full Configuration and verify all fields") {
    runWithRollback {
      provider.deleteUser(TestUserName)

      val config = Configuration(
        zoom = Some(5L),
        east = Some(2000L),
        north = Some(1000),
        roles = Set("admin", "viite"),
        authorizedElys = Set(1, 2)
      )
      provider.addUser(TestUserName, config)

      val userOpt = provider.getUser(TestUserName)
      userOpt shouldBe defined

      val user = userOpt.get
      user.username shouldBe TestUserName
      user.configuration.zoom shouldBe Some(5L)
      user.configuration.east shouldBe Some(2000L)
      user.configuration.north shouldBe Some(1000L)
      user.configuration.roles should contain allOf ("admin", "viite")
      user.configuration.authorizedElys should contain allOf (1, 2)
    }
  }
}
