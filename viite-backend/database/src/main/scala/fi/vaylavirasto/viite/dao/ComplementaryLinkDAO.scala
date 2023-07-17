package fi.vaylavirasto.viite.dao

import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.client.kgv.FilterOgc.withRoadNumbersFilter
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.vaylavirasto.viite.geometry.{BoundingRectangle, Point}
import fi.vaylavirasto.viite.model.{AdministrativeClass, LifecycleStatus, LinkGeomSource, RoadLink, TrafficDirection}
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import org.joda.time.DateTime
import net.postgis.jdbc.geometry.GeometryBuilder
import org.slf4j.LoggerFactory
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ComplementaryLinkDAO extends BaseDAO {
  val selectFromComplementaryLink =
    """
       SELECT id,adminclass,municipalitycode,lifecyclestatus,horizontallength,starttime,versionstarttime,sourcemodificationtime,geometry
       FROM complementary_link_table
    """

  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransactionNewOrExisting(f)

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

  private implicit val getRoadlink: GetResult[RoadLink] = new GetResult[RoadLink] {
    def apply(r: PositionedResult): RoadLink = {
      val UnknownMunicipality = -1
      val linkId = r.nextString()
      val administrativeClass = AdministrativeClass(r.nextInt())
      val municipalityCodeOption = r.nextIntOption()
      val municipalityCode = if (municipalityCodeOption.isEmpty) UnknownMunicipality else municipalityCodeOption.get
      val lifecycleStatus = LifecycleStatus(r.nextInt())
      val length = r.nextDouble()
      val modifiedAt = extractModifiedAt(Map(
        "starttime"              -> r.nextDateOption.map(d => formatter.parseDateTime(d.toString)),
        "versionstarttime"       -> r.nextDateOption.map(d => formatter.parseDateTime(d.toString)),
        "sourcemodificationtime" -> r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      )).map(_.toString())

      val geom = GeometryBuilder.geomFromString(r.nextString())
      var geometry: Seq[Point] = Seq()
      for (i <- 1 to geom.numPoints()) {
        val point = geom.getPoint(i - 1)
        geometry = geometry :+ Point(point.x, point.y, point.z)
      }

      RoadLink(linkId, geometry, length, administrativeClass, TrafficDirection.UnknownDirection, modifiedAt, None, lifecycleStatus, LinkGeomSource.ComplementaryLinkInterface, municipalityCode, "")
    }
  }

  def fetchByLinkId(linkId: String): Option[RoadLink] = {
    fetchByLinkIds(Set(linkId)).headOption
  }

  def fetchByLinkIds(linkIds: Set[String]): List[RoadLink] = {
    if (linkIds.nonEmpty) {
      time(logger, "Fetch complementary data by linkIds") {
        val sql = s"""$selectFromComplementaryLink WHERE id IN (${linkIds.map(lid => {
          "'" + lid + "'"
        }).mkString(", ")})"""
        withDynTransaction(Q.queryNA[RoadLink](sql).list)
      }
    } else List()
  }

  def fetchByLinkIdsF(linkIds: Set[String]): Future[Seq[RoadLink]] = {
    Future(fetchByLinkIds(linkIds))
  }

  /**
     * Returns RoadLinks by municipality.
     */
  def queryByMunicipality(municipality: Int): Seq[RoadLink] = {
    time(logger, s"Fetch complementary data by municipality") {
      val sql = s"""$selectFromComplementaryLink WHERE municipalitycode = $municipality"""
      withDynTransaction(Q.queryNA[RoadLink](sql).list)
    }
  }

  def fetchComplementaryByMunicipalitiesF(municipality: Int): Future[Seq[RoadLink]] =
    Future(queryByMunicipality(municipality))

  def queryByRoadNumbersAndMunicipality(municipality: Int, roadNumbers: Seq[(Int, Int)]): Seq[RoadLink] = {
    val roadNumberFilters = withRoadNumbersFilter(roadNumbers, includeAllPublicRoads = true)
    time(logger, "Fetch complementary data by road numbers and municipality") {
      val sql = s"""$selectFromComplementaryLink WHERE municipalitycode = $municipality AND """ + roadNumberFilters
      withDynTransaction(Q.queryNA[RoadLink](sql).list)
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
  def queryByMunicipalitiesAndBounds(bounds: BoundingRectangle, municipalities: Set[Int], filter: Option[String]): Seq[RoadLink] = {
    val geometry = s"geometry && ST_MakeEnvelope(${bounds.leftBottom.x},${bounds.leftBottom.y},${bounds.rightTop.x},${bounds.rightTop.y},3067)"
    val municipalityFilter = if (municipalities.nonEmpty) Some(s" AND municipalitycode IN (${municipalities.mkString(",")})") else ""
    time(logger, "Fetch complementary data by road numbers and municipality") {
      val sql = s"$selectFromComplementaryLink WHERE $geometry " + municipalityFilter + filter.getOrElse("")
      withDynTransaction(Q.queryNA[RoadLink](sql.trim).list)
    }
  }
  /**
    * Returns road links. Uses Scala Future for concurrent operations.
    */
  def fetchByBoundsAndMunicipalitiesF(bounds: BoundingRectangle, municipalities: Set[Int]): Future[Seq[RoadLink]] = {
    Future(queryByMunicipalitiesAndBounds(bounds, municipalities, None))
  }

  def fetchWalkwaysByBoundsAndMunicipalitiesF(bounds: BoundingRectangle, municipalities: Set[Int]): Future[Seq[RoadLink]] = {
    Future(queryByMunicipalitiesAndBounds(bounds, municipalities, Some(" AND roadclass = 12314")))
  }

}
