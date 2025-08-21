package fi.vaylavirasto.viite.dao

import fi.liikennevirasto.digiroad2.user.Configuration
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC.runWithRollback
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class UserProviderDAOSpec extends AnyFunSuite with Matchers {

  val TestUserName1 = "userprovidertest1"
  val TestUserName2 = "userprovidertest2"

  val provider = new UserProviderDAO

  test("Create, get, update and delete 2 users with full Configuration and verify all fields") {
    runWithRollback {
      // --- 1. Create users ---
      val config1 = Configuration(
        zoom = Some(5),
        east = Some(2000L),
        north = Some(1000L),
        roles = Set("admin", "viite"),
        authorizedElys = Set(1, 2)
      )
      provider.addUser(TestUserName1, config1)

      val config2 = Configuration(
        zoom = Some(3),
        east = Some(1111L),
        north = Some(2222L),
        roles = Set("viewer"),
        authorizedElys = Set(4)
      )
      provider.addUser(TestUserName2, config2)

      // --- 2. Get and verify created users ---
      val user1BeforeUpdate = provider.getUser(TestUserName1).get
      val user2BeforeUpdate = provider.getUser(TestUserName2).get

      user1BeforeUpdate.configuration.zoom shouldBe Some(5)
      user1BeforeUpdate.configuration.roles should contain allOf ("admin", "viite")
      user2BeforeUpdate.configuration.zoom shouldBe Some(3)
      user2BeforeUpdate.configuration.roles should contain only "viewer"

      // --- 3. Update users ---
      val updatedConfig1 = config1.copy(
        zoom = Some(10),
        east = Some(9999L),
        north = Some(8888L),
        roles = Set("viite"),
        authorizedElys = Set(3)
      )

      val updatedConfig2 = config2.copy(
        zoom = Some(7),
        east = Some(5555L),
        north = Some(4444L),
        roles = Set("editor"),
        authorizedElys = Set(5, 6)
      )

      provider.updateUsers(List(
        user1BeforeUpdate.copy(configuration = updatedConfig1),
        user2BeforeUpdate.copy(configuration = updatedConfig2)
      ))

      // --- 4. Get and verify updates ---
      val user1AfterUpdate = provider.getUser(TestUserName1).get
      val user2AfterUpdate = provider.getUser(TestUserName2).get

      user1AfterUpdate.configuration.zoom shouldBe Some(10)
      user1AfterUpdate.configuration.east shouldBe Some(9999L)
      user1AfterUpdate.configuration.roles should contain only "viite"
      user1AfterUpdate.configuration.authorizedElys should contain only 3

      user2AfterUpdate.configuration.zoom shouldBe Some(7)
      user2AfterUpdate.configuration.east shouldBe Some(5555L)
      user2AfterUpdate.configuration.roles should contain only "editor"
      user2AfterUpdate.configuration.authorizedElys should contain allOf (5, 6)

      // --- 5. Delete users ---
      provider.deleteUser(TestUserName1)
      provider.deleteUser(TestUserName2)

      provider.getUser(TestUserName1) shouldBe empty
      provider.getUser(TestUserName2) shouldBe empty
    }
  }
}
