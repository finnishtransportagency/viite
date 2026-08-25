package fi.vaylavirasto.viite.dao

import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC.runWithTransaction
import fi.vaylavirasto.viite.postgis.SessionProvider.session
import org.json4s.jackson.JsonMethods.parse
import scalikejdbc._

// Cache of Velho ("Luokitusrekisteri") object geometries, refreshed once a day by an external job (see VelhoApi.refreshVelhoCache).
object VelhoGeometryCacheDAO extends BaseDAO {

  /** Builds a GeoJSON FeatureCollection (EPSG:4326) from the cached rows for the given targetClass. */
  def fetchFeatureCollection(targetClass: String): Map[String, Any] = {
    val features = sql"""
      SELECT oid, ST_AsGeoJSON(geometry) AS geometry_json
      FROM velho_geometry_cache
      WHERE target_class = $targetClass
    """
      .map(rs => Map("type" -> "Feature", "properties" -> Map("oid" -> rs.string("oid")), "geometry" -> parse(rs.string("geometry_json")).values))
      .list()
      .apply()

    Map("type" -> "FeatureCollection", "features" -> features)
  }

  /** Replaces all cached rows for the given targetClass with `rows` (oid -> geometry as GeoJSON). */
  def replaceAll(targetClass: String, namespace: String, rows: Seq[(String, String)]): Unit = {
    runWithTransaction {
      runUpdateToDb(sql"DELETE FROM velho_geometry_cache WHERE target_class = $targetClass")

      if (rows.nonEmpty) {
        val batchParams: Seq[Seq[Any]] = rows.map { case (oid, geometryJson) =>
          Seq(oid, targetClass, namespace, geometryJson)
        }
        val query = sql"""
          INSERT INTO velho_geometry_cache (oid, target_class, namespace, geometry, fetched_time)
          VALUES (?, ?, ?, ST_SetSRID(ST_GeomFromGeoJSON(?), 4326), now())
        """
        runBatchUpdateToDb(query, batchParams)
      }
    }
  }
}
