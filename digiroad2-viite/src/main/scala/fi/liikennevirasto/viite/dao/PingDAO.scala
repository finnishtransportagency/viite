package fi.liikennevirasto.viite.dao

import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

object PingDAO {

  private implicit val getDbTimeRow = new GetResult[String] {
    def apply(r: PositionedResult) = {
      r.nextString()
    }
  }

  def getDbTime(): String = {
    val query = "SELECT TO_CHAR(NOW(), 'YYYY-MM-DD HH24:MI:SS')"
    Q.queryNA[String](query).iterator.toSeq.head
  }

}
