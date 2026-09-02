package fi.vaylavirasto.viite.dao

import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC.runWithTransaction
import fi.vaylavirasto.viite.postgis.SessionProvider.session
import org.json4s.jackson.JsonMethods.parse
import scalikejdbc._

// Cache of Velho ("Luokitusrekisteri") object geometries, refreshed once a day by an external job (see VelhoApi.refreshVelhoCache).
object VelhoGeometryCacheDAO extends BaseDAO {

  /**
   * Builds a GeoJSON FeatureCollection (EPSG:4326) from the cached rows for the given targetClass.
   *
   * @param bbox Optional (minLon, minLat, maxLon, maxLat) in EPSG:4326; restricts rows to those
   *             whose bounding box overlaps it (uses the geometry GiST index), so panning/zooming
   *             the map only fetches/serializes the geometries currently in view.
   */
  def fetchFeatureCollection(targetClass: String, bbox: Option[(Double, Double, Double, Double)] = None): Map[String, Any] = {
    val bboxFilter = bbox match {
      case Some((minLon, minLat, maxLon, maxLat)) =>
        sqls"AND geometry && ST_MakeEnvelope($minLon, $minLat, $maxLon, $maxLat, 4326)"
      case None => sqls""
    }

    val features = sql"""
      SELECT oid, ST_AsGeoJSON(geometry) AS geometry_json
      FROM velho_geometry_cache
      WHERE target_class = $targetClass
      $bboxFilter
    """
      .map(rs => Map("type" -> "Feature", "properties" -> Map("oid" -> rs.string("oid")), "geometry" -> parse(rs.string("geometry_json")).values))
      .list()
      .apply()

    Map("type" -> "FeatureCollection", "features" -> features)
  }

  private def insertRows(targetClass: String, namespace: String, rows: Seq[(String, String)]): Unit = {
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

  /** Replaces all cached rows for the given targetClass with `rows` (oid -> geometry as GeoJSON). */
  def replaceAll(targetClass: String, namespace: String, rows: Seq[(String, String)]): Unit = {
    runWithTransaction {
      runUpdateToDb(sql"DELETE FROM velho_geometry_cache WHERE target_class = $targetClass")
      insertRows(targetClass, namespace, rows)
    }
  }

  /** Removes cached rows for a class before its refreshed geometry batches are stored. */
  def deleteAll(targetClass: String): Unit = runWithTransaction {
    runUpdateToDb(sql"DELETE FROM velho_geometry_cache WHERE target_class = $targetClass")
  }

  /** Stores one refreshed geometry batch in its own transaction. */
  def insertBatch(targetClass: String, namespace: String, rows: Seq[(String, String)]): Unit = runWithTransaction {
    insertRows(targetClass, namespace, rows)
  }
}
