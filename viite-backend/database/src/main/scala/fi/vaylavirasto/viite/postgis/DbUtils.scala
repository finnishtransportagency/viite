package fi.vaylavirasto.viite.postgis

//import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.vaylavirasto.viite.postgis.SessionProvider.session
import scalikejdbc.{NoExtractor, SQL}

/** BaseDAO functionality: duplicate for import/referencing problems.
 * Trade usage to BaseDAO functions where ever possible. */
object DbUtils {

  def runUpdateToDb(updateQuery: SQL[Nothing, NoExtractor]): Int = {
    updateQuery.update.apply()
  }

}
