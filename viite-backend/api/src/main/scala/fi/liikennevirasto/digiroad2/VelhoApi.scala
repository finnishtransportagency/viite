package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.client.velho.VelhoClient
import fi.liikennevirasto.digiroad2.util.ViiteProperties
import fi.vaylavirasto.viite.dao.VelhoGeometryCacheDAO
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC.runWithReadOnlySession
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.ScalatraServlet
import org.slf4j.LoggerFactory

/* Glossary: 
  namespace: nimiavaruus. Example: "kansalliset-luokitukset"
  targetClass: kohdeluokka. Example: "erikoiskuljetusreitit"
*/

/**
 * Serves Velho ("Luokitusrekisteri") object geometries from a DB cache. The cache itself is
 * (re)populated once a day by an external job calling POST /refreshVelhoCache, which fetches
 * fresh data from Velho via VelhoClient.
 */
class VelhoApi extends ScalatraServlet with JacksonJsonSupport {

  private val logger = LoggerFactory.getLogger(getClass)
  protected implicit val jsonFormats: Formats = DefaultFormats

  // (namespace, targetClass) pairs cached and served by this servlet.
  private val cachedObjectClasses = Seq(
    ("kansalliset-luokitukset", "erikoiskuljetusreitit"),
    ("varautumiseen-liittyvat-luokitukset", "varareitit")
  )

  private lazy val velhoClient = new VelhoClient(
    ViiteProperties.velhoTokenUrl, ViiteProperties.velhoApiUrl,
    ViiteProperties.velhoClientId, ViiteProperties.velhoClientSecret
  )

  // Expects JSON responses, and returns JSON responses.
  before() {
    contentType = formats("json")
  }

  // Returns the cached GeoJSON FeatureCollection (EPSG:4326) for targetClass.
  private def fetchCachedGeoJson(targetClass: String) = runWithReadOnlySession {
    VelhoGeometryCacheDAO.fetchFeatureCollection(targetClass)
  }

  // Erikoiskuljetusreitit (special transport routes)
  get("/specialTransportRoutes") {
    fetchCachedGeoJson("erikoiskuljetusreitit")
  }

  // Varareitit (detour routes)
  get("/detourRoutes") {
    fetchCachedGeoJson("varareitit")
  }

  // Called once a day by an external job to refresh the DB cache with fresh data from Velho.
  // Can be triggered in local Windows with: curl.exe -X POST "http://localhost:9080/api/viite/velho/refreshVelhoCache"
  post("/refreshVelhoCache") { // If this is changed, make sure to update Lambda function both in repo and AWS
    val results = cachedObjectClasses.map { case (namespace, targetClass) =>
      velhoClient.getObjectsWithGeometry(namespace, targetClass) match {
        case Right(objects) =>
          val rows = objects.map { case (oid, geometry) => (oid, compact(render(geometry))) }
          VelhoGeometryCacheDAO.replaceAll(targetClass, namespace, rows)
          targetClass -> Right(rows.size)
        case Left(error) =>
          logger.error(s"Velho cache refresh failed for $targetClass: ${error.content}")
          targetClass -> Left(error.content.getOrElse("error", "Unknown error"))
      }
    }

    val failures = results.collect { case (targetClass, Left(reason)) => targetClass -> reason }
    Map(
      "success" -> failures.isEmpty,
      "refreshed" -> results.collect { case (targetClass, Right(count)) => Map("targetClass" -> targetClass, "count" -> count) },
      "errors" -> failures.map { case (targetClass, reason) => Map("targetClass" -> targetClass, "reason" -> reason) }
    )
  }
}
