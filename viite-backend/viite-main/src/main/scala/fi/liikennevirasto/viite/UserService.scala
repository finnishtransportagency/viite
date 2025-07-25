package fi.liikennevirasto.viite

import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC
import fi.liikennevirasto.digiroad2.user.{Configuration, User, UserProvider}
import org.slf4j.LoggerFactory

class UserService(userProvider: UserProvider) {

  private val logger = LoggerFactory.getLogger(getClass)

  def createUser(username: String, config: Configuration): Either[String, Unit] = {
    if (Option(username).exists(_.trim.nonEmpty)) {
      try {
        PostGISDatabaseScalikeJDBC.runWithTransaction {
          userProvider.addUser(username.trim.toLowerCase, config)
        }
        Right(())
      } catch {
        case e: Exception =>
          logger.error(s"Failed to create user '$username'", e)
          Left(s"Käyttäjän '$username' luominen epäonnistui.")
      }
    } else {
      Left("Käyttäjätunnus ei saa olla tyhjä.")
    }
  }

  def deleteUser(username: String): Either[String, Unit] = {
    if (Option(username).exists(_.trim.nonEmpty)) {
      try {
        PostGISDatabaseScalikeJDBC.runWithTransaction {
          userProvider.deleteUser(username.trim)
        }
        Right(())
      } catch {
        case e: Exception =>
          logger.error(s"Failed to delete user '$username'", e)
          Left(s"Käyttäjän '$username' poistaminen epäonnistui.")
      }
    } else {
      Left("Käyttäjätunnus ei saa olla tyhjä.")
    }
  }

  def updateUsers(users: List[User]): Either[String, Unit] = {
    try {
      PostGISDatabaseScalikeJDBC.runWithTransaction {
        userProvider.updateUsers(users)
      }
      Right(())
    } catch {
      case e: Exception =>
        logger.error("Failed to update users", e)
        Left("Käyttäjien päivittäminen epäonnistui.")
    }
  }

  def getAllUsers: Seq[User] = {
    try {
      PostGISDatabaseScalikeJDBC.runWithReadOnlySession {
        userProvider.getAllUsers
      }
    } catch {
      case e: Exception =>
        logger.error("Failed to fetch all users", e)
        Seq.empty
    }
  }
}
