package fi.vaylavirasto.viite.postgis

import scalikejdbc.DBSession

object SessionHolder {
  // ThreadLocal to store the current session (if any)
  private val currentSession = new ThreadLocal[Option[DBSession]]()

  /**
   * Sets the current thread's session.
   * This is typically called at the beginning of a transaction.
   *
   * @param session The DBSession to set
   */
  def setSession(session: DBSession): Unit = currentSession.set(Some(session))

  /**
   * Clears the current thread's session.
   * This should be called at the end of a transaction to clean up.
   */
  def clearSession(): Unit = currentSession.remove()

  /**
   * Retrieves the current session.
   * If a test session is set, it returns that session.
   * Otherwise, throws error.
   *
   * @return The current DBSession for this thread
   */
  def getSession: DBSession = currentSession.get().getOrElse(
    throw new IllegalStateException("No  database session set for this thread")
  )
}
