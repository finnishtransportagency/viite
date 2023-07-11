package fi.vaylavirasto.viite.dao

import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.user.Configuration
import fi.liikennevirasto.digiroad2.util.SqlScriptRunner._
import org.scalatest.{FunSuite, Matchers}
import slick.jdbc.JdbcBackend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

class PostGISUserProviderSpec extends FunSuite with Matchers {

  def runWithRollback[T](f: => T): T = {
    Database.forDataSource(PostGISDatabase.ds).withDynTransaction {
      val t = f
      dynamicSession.rollback()
      t
    }
  }

  val TestUserName = "userprovidertest"
  val north = 1000

  val provider = new PostGISUserProvider

  test("Test PostGISUserProvider.getUser() and PostGISUserProvider.createUser() " +
    "When trying to find a specific user name and creating a user for that user name " +
    "Then getUser() should return 'None' before creating, and the created user after creating it.") {
    runWithRollback {
      executeStatement("DELETE FROM service_user WHERE username = '" + TestUserName.toLowerCase() + "'")
      provider.getUser(TestUserName) shouldBe None
      provider.createUser(TestUserName, Configuration(north = Some(1000)))
      val user = provider.getUser(TestUserName).get
      user.username should be(TestUserName.toLowerCase)
      user.configuration.north should be(Some(north))
    }
  }
}
