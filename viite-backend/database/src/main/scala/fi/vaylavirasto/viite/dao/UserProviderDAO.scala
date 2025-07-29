package fi.vaylavirasto.viite.dao

import fi.liikennevirasto.digiroad2.user.{Configuration, User, UserProvider}
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC
import fi.vaylavirasto.viite.postgis.SessionProvider.session
import fi.vaylavirasto.viite.util.ViiteException
import org.json4s._
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.{read, write}
import scalikejdbc._

class UserProviderDAO extends BaseDAO with UserProvider {
  implicit val formats: Formats = Serialization.formats(NoTypeHints)

  object User extends SQLSyntaxSupport[User] {
    override val tableName = "service_user"
    def apply(rs: WrappedResultSet): User = new User(
      rs.long("id"),
      rs.string("username"),
      read[Configuration](rs.string("configuration"))
    )
  }

  def getUser(username: String): Option[User] = {
    if (username == null) None
    else {
      val query = sql"""
       SELECT id, username, configuration
       FROM service_user
       WHERE username = ${username}
       """
      runSelectSingleOption(query.map(User.apply))
    }
  }

  def deleteUser(username: String): Unit = {
    runUpdateToDb(
      sql"""
      DELETE FROM service_user
      WHERE username = $username
    """
    )
  }

  def getAllUsers: Seq[User] = {
    val query = sql"""
    SELECT id, username, configuration
    FROM service_user
    ORDER BY username
  """
    query.map(User.apply).list.apply()
  }

  def addUser(username: String, config: Configuration): Unit = {
    runUpdateToDb(
      sql"""
      INSERT INTO service_user (id, username, configuration)
      VALUES (
        nextval('service_user_seq'),
        ${username},
        ${write(config)}
      )
    """
    )
  }

  def updateUsers(users: List[User]): Unit = {
    users.foreach { user =>
      runUpdateToDb(
        sql"""
        UPDATE service_user
        SET configuration = ${write(user.configuration)}
        WHERE LOWER(username) = ${user.username}
      """
      )
    }
  }

}
