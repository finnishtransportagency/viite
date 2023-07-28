package fi.vaylavirasto.viite.dao

import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession
import fi.liikennevirasto.digiroad2.user.{Configuration, User, UserProvider}
import fi.vaylavirasto.viite.geometry.Point
import org.json4s._
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.{read, write}
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult}

class PostGISUserProvider extends BaseDAO with UserProvider {
  implicit val formats: Formats = Serialization.formats(NoTypeHints)
  implicit val getUser: GetResult[User] = new GetResult[User] {
    def apply(r: PositionedResult) = {
     User(r.nextLong(), r.nextString(), read[Configuration](r.nextString()))
    }
  }
  implicit val getUserArea: GetResult[Point] = new GetResult[Point] {
    def apply(r: PositionedResult) = {
      Point(r.nextDouble(), r.nextDouble(), r.nextDouble())
    }
  }

  def createUser(username: String, config: Configuration): Unit = {
    PostGISDatabase.withDynSession {
      runUpdateToDb(
        s"""insert into service_user (id, username, configuration)""" +
        s"""values (nextval('service_user_seq'), '${username.toLowerCase}', '${write(config)}')"""
      )
    }
  }

  def getUser(username: String): Option[User] = {
    if (username == null) return None
    PostGISDatabase.withDynSession {
      val query = s"""select id, username, configuration from service_user where lower(username) = '${username.toLowerCase}'"""
      sql"""#$query""".as[User].firstOption
    }
  }

  def saveUser(user: User): User = {
    PostGISDatabase.withDynSession {
      runUpdateToDb(
        s"""
           |update service_user
           |set configuration = '${write(user.configuration)}'
           |where lower(username) = '${user.username.toLowerCase}'
        """.stripMargin)
      user
    }
  }

  def deleteUser(username: String): Unit = {
    PostGISDatabase.withDynSession {
      runUpdateToDb(s"""delete from service_user where lower(username) = '${username.toLowerCase}'""")
    }
  }

}
