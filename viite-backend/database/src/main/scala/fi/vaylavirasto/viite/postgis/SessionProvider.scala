package fi.vaylavirasto.viite.postgis

import fi.liikennevirasto.digiroad2.client.kgv.Extractor.logger
import scalikejdbc._

/**
 * Provides a thread-local session for ScalikeJDBC to handle transactions.
 *
 * This object maintains thread-local state for database sessions and transaction status,
 * ensuring that database operations are properly scoped and preventing nested transactions.
 *
 * The session management is used in two ways:
 * 1. Implicit session access: When SessionProvider.session is imported, queries can use
 *    the implicit session without explicitly passing it as a parameter
 * 2. Through BaseScalikeDAO: DAO classes use these sessions via BaseScalikeDAO trait methods
 *    (e.g., runSelectQuery) without needing direct imports
 */
object SessionProvider {
  // Tracks the current database session per thread
  private val threadLocalSession = new ThreadLocal[DBSession]()

  // Tracks whether a transaction is currently open in the current thread
  private val transactionOpen = new ThreadLocal[Boolean] {
    override def initialValue(): Boolean = false
  }
  def isTransactionOpen: Boolean = transactionOpen.get()


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
   * Executes code block in a database session, preventing nested transactions.
   * Sets up the session, runs the operation, and ensures cleanup afterwards by restoring previous session.
   *
   * @param dbSession The `DBSession` to use during `f`.
   * @param f         The block of code to execute.
   * @tparam T        The return type of `f`.
   * @throws IllegalStateException if called within an existing transaction
   */
  def withSession[T](dbSession: DBSession)(f: => T): T = {
    if (isTransactionOpen && !dbSession.isReadOnly) { // Allow nested transactions for read-only operations
      throw new IllegalStateException("Nested transactions are not allowed")
    }
    try {
      transactionOpen.set(true)
      val previousSession = threadLocalSession.get()   // Save previous
      threadLocalSession.set(dbSession)
      try {
        f
      } finally {
        threadLocalSession.set(previousSession)        // Restore previous
      }
    } finally {
      transactionOpen.set(false)
    }
  }

}
