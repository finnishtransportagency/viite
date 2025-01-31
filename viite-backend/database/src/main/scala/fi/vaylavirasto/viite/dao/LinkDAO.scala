package fi.vaylavirasto.viite.dao

import org.joda.time.DateTime
import scalikejdbc._
import scalikejdbc.jodatime.JodaWrappedResultSet.fromWrappedResultSetToJodaWrappedResultSet

case class Link(id: String, source: Long, adjustedTimestamp: Long, createdTime: Option[DateTime]){
  def this (id: Long, source: Long, adjustedTimestamp: Long, createdTime: Option[DateTime]) =
   this(id.toString, source, adjustedTimestamp, createdTime)
}

object Link extends SQLSyntaxSupport[Link] {
  override val tableName = "LINK"

  def apply(rs: WrappedResultSet): Link = Link(
    id                = rs.string("id"),
    source            = rs.long("source"),
    adjustedTimestamp = rs.long("adjusted_timestamp"),
    createdTime       = rs.jodaDateTimeOpt("created_time")
  )
}

object LinkDAO extends BaseDAO {

  def fetch(id: String): Option[Link] = {
    val query =
      sql"""
          SELECT link.id, link.source, link.adjusted_timestamp, link.created_time
          FROM link WHERE link.id = $id
          """
    runSelectSingleOption(query.map(Link.apply))
  }
  def create(id: String, adjustedTimestamp: Long, source: Long): Unit = {
    runUpdateToDb(sql"""
      INSERT INTO link (id, source, adjusted_timestamp)
      VALUES           ($id, $source, $adjustedTimestamp)
      """)
  }

  def createIfEmptyFetch(id: String, adjustedTimestamp: Long, source: Long): Unit = {
    if (fetch(id).isEmpty) {
      runUpdateToDb(sql"""
        INSERT INTO link (id, source, adjusted_timestamp)
        VALUES ($id, $source, $adjustedTimestamp)
      """)
    }
  }

  def fetchMaxAdjustedTimestamp(): Long = {
    val query = sql"""
      SELECT max(adjusted_timestamp)
      FROM link
      WHERE link.source IN (1, 4)
    """
    runSelectSingleFirstWithType[Long](query)
  }

}
