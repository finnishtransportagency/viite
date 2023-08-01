package fi.vaylavirasto.viite.dao

import fi.liikennevirasto.digiroad2.user.Configuration
import fi.vaylavirasto.viite.postgis.DbUtils.runUpdateToDb
import fi.vaylavirasto.viite.postgis.PostGISDatabase
import fi.vaylavirasto.viite.postgis.PostGISDatabase.runWithRollback
import org.scalatest.{FunSuite, Matchers}

class PostGISUserProviderSpec extends FunSuite with Matchers {

  val TestUserName = "userprovidertest"
  val north = 1000

  val provider = new PostGISUserProvider

  test("Test PostGISUserProvider.getUser() and PostGISUserProvider.createUser() " +
    "When trying to find a specific user name and creating a user for that user name " +
    "Then getUser() should return 'None' before creating, and the created user after creating it.") {
    runWithRollback {
      PostGISDatabase.withDynSession {
        runUpdateToDb(s"""DELETE FROM service_user WHERE username = '${TestUserName.toLowerCase()}'""")
      }
      provider.getUser(TestUserName) shouldBe None
      provider.createUser(TestUserName, Configuration(north = Some(1000)))
      val user = provider.getUser(TestUserName).get
      user.username should be(TestUserName.toLowerCase)
      user.configuration.north should be(Some(north))
    }
  }
}
