package fi.vaylavirasto.viite.dao

import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.client.kgv.FilterOgc.withRoadNumbersFilter
import fi.vaylavirasto.viite.geometry.{BoundingRectangle, Point}
import fi.vaylavirasto.viite.model.{AdministrativeClass, LifecycleStatus, LinkGeomSource, RoadLink, TrafficDirection}
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC
import org.joda.time.DateTime
import net.postgis.jdbc.geometry.GeometryBuilder
import scalikejdbc._
import scalikejdbc.jodatime.JodaWrappedResultSet.fromWrappedResultSetToJodaWrappedResultSet

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class ComplementaryLink(
                              id: String,
                              datasource: Int,
                              adminclass: Int,
                              municipalitycode: Int,
                              featureclass: Int,
                              roadclass: Int,
                              roadnamefin: Option[String],
                              roadnameswe: Option[String],
                              roadnamesme: Option[String],
                              roadnamesmn: Option[String],
                              roadnamesms: Option[String],
                              roadnumber: Int,
                              roadpartnumber: Int,
                              surfacetype: Int,
                              lifecyclestatus: Int,
                              directiontype: Int,
                              surfacerelation: Int,
                              xyaccuracy: Double,
                              zaccuracy: Double,
                              horizontallength: Double,
                              addressfromleft: Int,
                              addresstoleft: Int,
                              addressfromright: Int,
                              addresstoright: Int,
                              starttime: DateTime,
                              versionstarttime: DateTime,
                              sourcemodificationtime: DateTime,
                              geometry: Seq[Point],
                              ajorata: Int,
                              vvh_id: String
                            )

class ComplementaryLinkDAO extends BaseDAO {

  lazy val selectFromComplementaryLink =
    sqls"""
       SELECT id, adminclass, municipalitycode, lifecyclestatus, horizontallength, starttime,
         versionstarttime, sourcemodificationtime, geometry
       FROM complementary_link_table
    """


  object RoadLink extends SQLSyntaxSupport[RoadLink] {
    private val UnknownMunicipality = -1

    def apply(rs: WrappedResultSet): RoadLink = {
      val linkId              = rs.string("id")
      val administrativeClass = AdministrativeClass(rs.int("adminclass"))
      val municipalityCode    = rs.intOpt("municipalitycode").getOrElse(UnknownMunicipality)
      val lifecycleStatus     = LifecycleStatus(rs.int("lifecyclestatus"))
      val length              = rs.double("horizontallength")

      // Handle the modified dates
      val modifiedAt = extractModifiedAt(Map(
        "starttime"              -> rs.jodaDateTimeOpt("starttime").map(d => new DateTime(d)),
        "versionstarttime"       -> rs.jodaDateTimeOpt("versionstarttime").map(d => new DateTime(d)),
        "sourcemodificationtime" -> rs.jodaDateTimeOpt("sourcemodificationtime").map(d => new DateTime(d))
      )).map(_.toString())

      // Handle geometry
      val geomString = rs.string("geometry")
      val geom = GeometryBuilder.geomFromString(geomString)
      val geometry = (0 until geom.numPoints()).map { i =>
        val point = geom.getPoint(i)
        Point(point.x, point.y, point.z)
      }

      new RoadLink(
        linkId              = linkId,
        geometry            = geometry,
        length              = length,
        administrativeClass = administrativeClass,
        trafficDirection    = TrafficDirection.UnknownDirection,
        modifiedAt          = modifiedAt,
        modifiedBy          = None,
        lifecycleStatus     = lifecycleStatus,
        linkSource          = LinkGeomSource.ComplementaryLinkInterface,
        municipalityCode    = municipalityCode,
        sourceId            = ""
      )
    }
  }

  def runWithReadOnlySession[T](f: => T): T = PostGISDatabaseScalikeJDBC.runWithReadOnlySession(f)

  def extractModifiedAt(attributes: Map[String, Option[DateTime]]): Option[DateTime] = {
    def toLong(anyValue: Option[Any]): Option[Long] = {
      anyValue.map(_.asInstanceOf[DateTime].getMillis)
    }
    def compareDateMillisOptions(a: Option[Long], b: Option[Long]): Option[Long] = {
      (a, b) match {
        case (Some(firstModifiedAt), Some(secondModifiedAt)) =>
          if (firstModifiedAt > secondModifiedAt)
            Some(firstModifiedAt)
          else
            Some(secondModifiedAt)
        case (Some(firstModifiedAt), None) => Some(firstModifiedAt)
        case (None, Some(secondModifiedAt)) => Some(secondModifiedAt)
        case (None, None) => None
      }
    }
    val createdDate = toLong(attributes("starttime"))
    val lastEditedDate = toLong(attributes("versionstarttime"))
    val geometryEditedDate = toLong(attributes("sourcemodificationtime"))
    compareDateMillisOptions(lastEditedDate, geometryEditedDate).orElse(createdDate).map(modifiedTime => new DateTime(modifiedTime))
  }

  def create(complementaryLink: ComplementaryLink): Long = {
    val geometryWKT = s"LINESTRING Z(${complementaryLink.geometry.map(p => s"${p.x} ${p.y} ${p.z}").mkString(", ")})"
    val updateQuery = sql"""
    INSERT INTO complementary_link_table (
      id,
      datasource,
      adminclass,
      municipalitycode,
      featureclass,
      roadclass,
      roadnamefin,
      roadnameswe,
      roadnamesme,
      roadnamesmn,
      roadnamesms,
      roadnumber,
      roadpartnumber,
      surfacetype,
      lifecyclestatus,
      directiontype,
      surfacerelation,
      xyaccuracy,
      zaccuracy,
      horizontallength,
      addressfromleft,
      addresstoleft,
      addressfromright,
      addresstoright,
      starttime,
      versionstarttime,
      sourcemodificationtime,
      geometry,
      ajorata,
      vvh_id
    ) VALUES (
      ${complementaryLink.id},
      ${complementaryLink.datasource},
      ${complementaryLink.adminclass},
      ${complementaryLink.municipalitycode},
      ${complementaryLink.featureclass},
      ${complementaryLink.roadclass},
      ${complementaryLink.roadnamefin},
      ${complementaryLink.roadnameswe},
      ${complementaryLink.roadnamesme},
      ${complementaryLink.roadnamesmn},
      ${complementaryLink.roadnamesms},
      ${complementaryLink.roadnumber},
      ${complementaryLink.roadpartnumber},
      ${complementaryLink.surfacetype},
      ${complementaryLink.lifecyclestatus},
      ${complementaryLink.directiontype},
      ${complementaryLink.surfacerelation},
      ${complementaryLink.xyaccuracy},
      ${complementaryLink.zaccuracy},
      ${complementaryLink.horizontallength},
      ${complementaryLink.addressfromleft},
      ${complementaryLink.addresstoleft},
      ${complementaryLink.addressfromright},
      ${complementaryLink.addresstoright},
      ${complementaryLink.starttime},
      ${complementaryLink.versionstarttime},
      ${complementaryLink.sourcemodificationtime},
      ST_GeomFromText(${geometryWKT}, 3067),
      ${complementaryLink.ajorata},
      ${complementaryLink.vvh_id}
    )
  """
    runUpdateToDb(updateQuery)
  }

  def fetchByLinkId(linkId: String, readOnlySession: Boolean = true): Option[RoadLink] = {
    if (readOnlySession)
      fetchByLinkIdsInReadOnlySession(Set(linkId)).headOption
    else
      fetchByLinkIds(Set(linkId)).headOption
  }

  def fetchByLinkIdsInReadOnlySession(linkIds: Set[String]): List[RoadLink] = {
    if (linkIds.nonEmpty) {
      time(logger, "Fetch complementary data by linkIds") {
        val query =
          sql"""
              $selectFromComplementaryLink
              WHERE id IN ($linkIds)
          """
        runWithReadOnlySession(runSelectQuery(query.map(RoadLink.apply))
        )
      }
    } else List()
  }

  def fetchByLinkIds(linkIds: Set[String]): List[RoadLink] = {
    val query = sql"""
              $selectFromComplementaryLink
              WHERE id IN ($linkIds)
          """
    runSelectQuery(query.map(RoadLink.apply))
  }

  def fetchByLinkIdsF(linkIds: Set[String]): Future[Seq[RoadLink]] = {
    Future(fetchByLinkIdsInReadOnlySession(linkIds))
  }

  /**
     * Returns RoadLinks by municipality.
     */
  def queryByMunicipality(municipality: Int): Seq[RoadLink] = {
    time(logger, s"Fetch complementary data by municipality") {
      val query = sql"""
          $selectFromComplementaryLink
          WHERE municipalitycode = $municipality
          """
      runWithReadOnlySession(runSelectQuery(query.map(RoadLink.apply)))
    }
  }

  def fetchComplementaryByMunicipalitiesF(municipality: Int): Future[Seq[RoadLink]] =
    Future(queryByMunicipality(municipality))

  def queryByRoadNumbersAndMunicipality(municipality: Int, roadNumbers: Seq[(Int, Int)]): Seq[RoadLink] = {
    val roadNumberFilters = withRoadNumbersFilter(roadNumbers, includeAllPublicRoads = true)
    time(logger, "Fetch complementary data by road numbers and municipality") {
      val query =
        sql"""
          $selectFromComplementaryLink
          WHERE municipalitycode = $municipality AND roadNumberFilter
          """
      runWithReadOnlySession(runSelectQuery(query.map(RoadLink.apply)))
    }
  }
  /**
    * Returns a sequence of RoadLinks. Uses Scala Future for concurrent operations.
    */
  def fetchByMunicipalityAndRoadNumbersF(municipality: Int, roadNumbers: Seq[(Int, Int)]): Future[Seq[RoadLink]] = {
    Future(queryByRoadNumbersAndMunicipality(municipality, roadNumbers))
  }

  /**
    * Returns road links in bounding box area. Municipalities are optional.
    */
  def queryByMunicipalitiesAndBounds(bounds: BoundingRectangle, municipalities: Set[Int], filter: Option[SQLSyntax]): Seq[RoadLink] = {
    val geometry = sqls"geometry && ST_MakeEnvelope(${bounds.leftBottom.x},${bounds.leftBottom.y},${bounds.rightTop.x},${bounds.rightTop.y},3067)"
    val municipalityFilter = if (municipalities.nonEmpty) Some(sqls" AND municipalitycode IN (${municipalities})") else sqls""

    time(logger, "Fetch complementary data by road numbers and municipality") {
      val query = sql"""
      $selectFromComplementaryLink
      WHERE $geometry
      $municipalityFilter ${filter.getOrElse(sqls"")}
      """

      runWithReadOnlySession(runSelectQuery(query.map(RoadLink.apply)))
    }
  }
  /**
    * Returns road links. Uses Scala Future for concurrent operations.
    */
  def fetchByBoundsAndMunicipalitiesF(bounds: BoundingRectangle, municipalities: Set[Int]): Future[Seq[RoadLink]] = {
    Future(queryByMunicipalitiesAndBounds(bounds, municipalities, None))
  }

  def fetchWalkwaysByBoundsAndMunicipalitiesF(bounds: BoundingRectangle, municipalities: Set[Int]): Future[Seq[RoadLink]] = {
    Future(queryByMunicipalitiesAndBounds(bounds, municipalities, Some(sqls" AND roadclass = 12314")))
  }

}
