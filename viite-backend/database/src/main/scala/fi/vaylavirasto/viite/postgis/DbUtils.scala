package fi.vaylavirasto.viite.postgis

//import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

import java.sql.Date

/** BaseDAO functionality: duplicate for import/referencing problems.
  * Trade usage to BaseDAO functions where ever possible. */
object DbUtils {

  /* OLD Slick 3.0.0 way to run direct SQL update queries. */
  def runUpdateToDb(updateQuery: String) = {
    sqlu"""#$updateQuery""".buildColl.toList.head
  }

}
