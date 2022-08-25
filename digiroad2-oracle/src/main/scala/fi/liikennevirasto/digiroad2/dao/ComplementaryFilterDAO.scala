package fi.liikennevirasto.digiroad2.dao

import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.{StaticQuery => Q}

class ComplementaryFilterDAO {

  def fetchAll(): Seq[String] = {
    val sql = s"""SELECT * FROM COMPLEMENTARY_FILTER"""
    Q.queryNA[String](sql).list
  }

}
