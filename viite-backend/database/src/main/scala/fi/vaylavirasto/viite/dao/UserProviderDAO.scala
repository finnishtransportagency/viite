package fi.vaylavirasto.viite.dao

import fi.liikennevirasto.digiroad2.user.{Configuration, User, UserProvider}
import fi.vaylavirasto.viite.util.ViiteException
import org.json4s._
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.{read, write}
import scalikejdbc._


class UserProviderDAO extends BaseDAO with UserProvider {
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
      runUpdateToDb(
        sql"""
        INSERT INTO service_user (id, username, configuration)
        VALUES (nextval('service_user_seq'), ${username.toLowerCase}, ${write(config)})
            """
      )
  }

  /**
   * Get user by username
   * @param username: String
   * @return User if found, None if not found
   * @throws ViiteException if multiple users found with the same username or database error
   */
  def getUser(username: String): Option[User] = {
    if (username == null) None
    else {
      val query = sql"""
         SELECT id, username, configuration
         FROM service_user
         WHERE lower(username) = ${username.toLowerCase}
         """

      try {
        runSelectSingleOption(query.map(User.apply))
      } catch {
        case e: TooManyRowsException =>
          throw ViiteException(s"Multiple users found with username $username")
        case e: Exception =>
          throw ViiteException(s"Database error while retrieving user $username: ${e.getMessage}")
      }
    }
  }

  def saveUser(user: User): User = {
      runUpdateToDb(
        sql"""
           UPDATE service_user
           SET configuration = ${write(user.configuration)}
           WHERE lower(username) = ${user.username.toLowerCase}
        """
      )
      user
  }

  def deleteUser(username: String): Unit = {
      runUpdateToDb(
        sql"""
             DELETE FROM service_user
             WHERE lower(username) = ${username.toLowerCase}
             """
      )
  }

}
