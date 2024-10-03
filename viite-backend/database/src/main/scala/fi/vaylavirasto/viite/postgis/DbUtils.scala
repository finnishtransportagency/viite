package fi.vaylavirasto.viite.postgis

//import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.vaylavirasto.viite.dao.ScalikeJDBCBaseDAO
import fi.vaylavirasto.viite.postgis.SessionProvider.session
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import scalikejdbc._

/** BaseDAO functionality: duplicate for import/referencing problems.
  * Trade usage to BaseDAO functions where ever possible. TODO Check if this is needed or not with Base(Scalike)DAO */
object DbUtils {

  /* OLD Slick 3.0.0 way to run direct SQL update queries. */
  def runUpdateToDb(updateQuery: String) = {
    sqlu"""#$updateQuery""".buildColl.toList.head
  }

}
// ScalikeJDBC version
object DbUtilsScalike  extends  ScalikeJDBCBaseDAO {

  def runUpdateToDbTestsScalike(updateQuery: SQL[Nothing, NoExtractor]): Int = {
    updateQuery.update.apply()
  }

}

