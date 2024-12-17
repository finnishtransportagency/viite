package fi.vaylavirasto.viite.dao

import scalikejdbc._

object PingDAO extends BaseDAO {

  // Get the current time from the database
  // Example how to use ScalikeCDBC on basic level without helper methods
  def getDbTime(implicit session: DBSession): String = {
    sql"""SELECT TO_CHAR(NOW(), 'YYYY-MM-DD HH24:MI:SS')"""
      .map(rs => rs.string(1)) // Get the first column as a string
      .single() // single returns the 1 row as an Option
      .apply() // Execute the query
      .getOrElse( // Throw an exception if the result is None
        throw new RuntimeException("Failed to get the current time from the database")
      )
  }
}
