package fi.liikennevirasto.digiroad2.user

trait UserProvider {
  val threadLocalUser: ThreadLocal[User] = new ThreadLocal[User]

  def clearCurrentUser(): Unit = {
    threadLocalUser.remove()
  }

  def setCurrentUser(user: User): Unit = {
    threadLocalUser.set(user)
  }

  def getCurrentUser: User = {
    threadLocalUser.get() match {
      case u: User => u
      case _ => throw new IllegalStateException("Current user not available")
    }
  }

  def addUser(username: String, config: Configuration): Unit
  def deleteUser(username: String): Unit
  def getUser(username: String): Option[User]
  def getAllUsers: Seq[User]
  def updateUsers(users: List[User]): Unit
}
