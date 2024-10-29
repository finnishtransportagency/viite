package fi.vaylavirasto.viite.dao

import fi.liikennevirasto.digiroad2.user.{Configuration, User, UserProvider}
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC
import org.json4s._
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.{read, write}
import scalikejdbc._


class PostGISUserProvider extends BaseDAO with UserProvider {
  implicit val formats: Formats = Serialization.formats(NoTypeHints)

  object User extends SQLSyntaxSupport[User]{
    override val tableName = "service_user"
    def apply(rs: WrappedResultSet): User = new User(
      rs.long("id"),
      rs.string("username"),
      read[Configuration](rs.string("configuration"))
    )
  }

  def createUser(username: String, config: Configuration): Unit = {
    PostGISDatabaseScalikeJDBC.runWithAutoCommit {
      runUpdateToDb(
        sql"""
        INSERT INTO service_user (id, username, configuration)
        VALUES (nextval('service_user_seq'), ${username.toLowerCase}, ${write(config)})
            """
      )
    }
  }

  def getUser(username: String): Option[User] = {
    if (username == null) return None
    PostGISDatabaseScalikeJDBC.runWithReadOnlySession {
      val query =
        sql"""
             SELECT id, username, configuration
             FROM service_user
             WHERE lower(username) = ${username.toLowerCase}
             """
      runSelectSingle(query.map(User.apply))
    }
  }

  def saveUser(user: User): User = {
    PostGISDatabaseScalikeJDBC.runWithAutoCommit {
      runUpdateToDb(
        sql"""
           UPDATE service_user
           SET configuration = ${write(user.configuration)}
           WHERE lower(username) = ${user.username.toLowerCase}
        """
      )
      user
    }
  }

  def deleteUser(username: String): Unit = {
    PostGISDatabaseScalikeJDBC.runWithAutoCommit {
      runUpdateToDb(
        sql"""
             DELETE FROM service_user
             WHERE lower(username) = ${username.toLowerCase}
             """
      )
    }
  }

}
