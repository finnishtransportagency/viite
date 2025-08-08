package fi.vaylavirasto.viite.postgis

import fi.liikennevirasto.digiroad2.client.kgv.Extractor.logger
import scalikejdbc._

/**
 * Provides a thread-local session for ScalikeJDBC to handle transactions.
 *
 * This object maintains thread-local state for database sessions and transaction status,
 * ensuring that database operations are properly scoped and preventing nested transactions.
 *
 * Ensures database operations run in proper session context and prevents nested transactions.
 * Sessions are available implicitly when SessionProvider.session is imported or through BaseScalikeDAO methods.
 */
object SessionProvider {
  // Tracks the current database session per thread
  private val threadLocalSession = new ThreadLocal[DBSession]()

  // Tracks whether write operations are blocked for the current thread
  private val writeOperationsBlocked = new ThreadLocal[Boolean]()

  /**
   * Provides implicit access to the current thread's database session.
   * This allows ScalikeJDBC queries to automatically use the correct session.
   *
   * @throws IllegalStateException if no session is currently set
   * @return The current DBSession for this thread
   */
  implicit def session: DBSession = threadLocalSession.get() match {
    case null =>
      val errorMsg = "No DBSession is set. Ensure you are within a transaction or session."
      logger.error(errorMsg)
      throw new IllegalStateException(errorMsg)
    case s => s
  }

  /**
   * Checks if write operations are currently allowed on this thread.
   * Throws SQLException if writes are blocked.
   */
  def checkWriteAllowed(): Unit = {
    if (Option(writeOperationsBlocked.get()).getOrElse(false)) {
      throw new java.sql.SQLException("Write operations not allowed in read-only session within transaction")
    }
  }

  /**
   * Executes code block in a database session, preventing nested transactions.
   * Sets up the session, runs the operation, and ensures cleanup afterwards by restoring previous session.
   *
   * @param newDbSession The `DBSession` to use during `f`.
   * @param f         The block of code to execute.
   * @tparam T        The return type of `f`.
   * @throws IllegalStateException if called within an existing transaction
   */
  def withSession[T](newDbSession: DBSession)(f: => T): T = {
    val currentSession = threadLocalSession.get()
    val currentWriteBlocked = Option(writeOperationsBlocked.get()).getOrElse(false)

    currentSession match {
      case null =>
        // No existing session - use new session normally
        executeWithSession(newDbSession, f, newDbSession.isReadOnly, currentWriteBlocked)

      case existing if newDbSession.isReadOnly && existing.tx.isDefined =>
        // Read-only joining transaction - keep existing session but block writes
        executeWithSession(existing, f, writeBlocked = true, currentWriteBlocked)

      case existing if newDbSession.tx.isDefined =>
        // Trying to start transaction inside existing session - forbidden
        val sessionType = if (existing.tx.isDefined) "transaction" else "session"
        throw new IllegalStateException(s"Cannot start transaction inside existing $sessionType")

      case _ =>
        // Replace current session with new one
        executeWithSession(newDbSession, f, newDbSession.isReadOnly, currentWriteBlocked)
    }
  }

  /**
   * Private helper to execute code with proper session and write-blocking setup/cleanup
   */
  private def executeWithSession[T](session: DBSession, f: => T, writeBlocked: Boolean, previousWriteBlocked: Boolean): T = {
    val previousSession = threadLocalSession.get()
    try {
      threadLocalSession.set(session)
      writeOperationsBlocked.set(writeBlocked)
      f
    } finally {
      threadLocalSession.set(previousSession)
      writeOperationsBlocked.set(previousWriteBlocked)
    }
  }
}
