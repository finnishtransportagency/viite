package fi.liikennevirasto.digiroad2.dao

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.client.vvh.Filter.withRoadNumbersFilter
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import org.joda.time.DateTime
import org.postgis.PGgeometry
import org.slf4j.LoggerFactory
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ComplementaryLinkDAO {
  protected def logger = LoggerFactory.getLogger(getClass)
  val formatter: DateTimeFormatter = ISODateTimeFormat.dateOptionalTimeParser()
  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)

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

      var attributes = Map[String, Any]()
      val linkId = r.nextString()
      attributes += "datasource" -> r.nextIntOption()
      val administrativeClass = AdministrativeClass(r.nextInt())
      val municipalityCode = r.nextInt()
      attributes += "municipalitycode" -> municipalityCode
      attributes += "featureclass " -> r.nextIntOption() // vvh MTKGROUP
      r.nextIntOption() // vvh MTKCLASS
      //      val featureClass = r.nextIntOption() match { // vvh MTKCLASS
//        case Some(roadclass) => Extractor.featureClassCodeToFeatureClass(roadclass)
//        case None            => FeatureClass.AllOthers
//      }
      attributes ++= Map(
        "roadnamefin"    -> r.nextStringOption(),
        "roadnameswe"    -> r.nextStringOption(),
        "roadnamesme"    -> r.nextStringOption(),
        "roadnamesmn"    -> r.nextStringOption(),
        "roadnamesms"    -> r.nextStringOption(),
        "roadnumber"     -> r.nextIntOption(),
        "roadpartnumber" -> r.nextIntOption(),
        "surfacetype"    -> r.nextIntOption()
      )
      val lifecycleStatus = LifecycleStatus(r.nextInt())
      r.nextIntOption() //TrafficDirection(r.nextIntOption().getOrElse(TrafficDirection.UnknownDirection.value))
      attributes ++= Map(
        "surfacerelation" -> r.nextIntOption(),
        "xyaccuracy"      -> r.nextDoubleOption(),
        "zaccuracy"       -> r.nextDoubleOption()
      )
      val length = r.nextDouble()
      Map(
        "addressfromleft"  -> r.nextIntOption(),
        "addresstoleft"    -> r.nextIntOption(),
        "addressfromright" -> r.nextIntOption(),
        "addresstoright"   -> r.nextIntOption()
      )
      val modifiedAt = extractModifiedAt(Map(
        "starttime"              -> r.nextDateOption.map(d => formatter.parseDateTime(d.toString)),
        "versionstarttime"       -> r.nextDateOption.map(d => formatter.parseDateTime(d.toString)),
        "sourcemodificationtime" -> r.nextDateOption.map(d => formatter.parseDateTime(d.toString)
      ))).map(_.toString())

      val geom = PGgeometry.geomFromString(r.nextString())
      var geometry: Seq[Point] = Seq()
      for (i <- 1 to geom.numPoints()) {
        val point = geom.getPoint(i - 1)
        geometry = geometry :+ Point(point.x, point.y, point.z)
      }

      val linkSource = LinkGeomSource.ComplementaryLinkInterface

//      RoadLink(linkId, municipalityCode, geometry, administrativeClass,
//         trafficDirection, featureClass , modifiedAt, attributes,
//        lifecycleStatus, linkSource, length)
//
      RoadLink(linkId, geometry, length, administrativeClass, -1, TrafficDirection.UnknownDirection, modifiedAt, None, attributes, lifecycleStatus, linkSource)

    }
  }

  def fetchByLinkId(linkId: String): Option[RoadLink] = {
    fetchByLinkIds(Set(linkId)).headOption
  }

  def fetchByLinkIds(linkIds: Set[String]): List[RoadLink] = {
    time(logger, "Fetch complementary data by linkIds") {
      val sql = s"""SELECT * FROM complementary_link_table WHERE id IN (${linkIds.map(lid => "'" + lid + "'").mkString(", ")})"""
      withDynTransaction(Q.queryNA[RoadLink](sql).list)
    }
  }

  def fetchByLinkIdsF(linkIds: Set[String]): Future[Seq[RoadLink]] = {
    Future(fetchByLinkIds(linkIds))
  }

  /**
     * Returns RoadLinks by municipality.
     */
  def queryByMunicipality(municipality: Int, filter: Option[String] = None): Seq[RoadLink] = {
    val filterString = if (filter.isDefined) " AND" + filter.get.replaceFirst("(?i)AND", "") else ""
    time(logger, s"Fetch complementary data by municipality (and ${filter})") {
      val sql = s"""SELECT * FROM complementary_link_table WHERE municipalitycode = $municipality""" + filterString
      withDynTransaction(Q.queryNA[RoadLink](sql).list)
    }
  }

  def fetchComplementaryByMunicipalitiesF(municipality: Int): Future[Seq[RoadLink]] =
    Future(queryByMunicipality(municipality))

  def queryByRoadNumbersAndMunicipality(municipality: Int, roadNumbers: Seq[(Int, Int)]): Seq[RoadLink] = {
    val roadNumberFilters = withRoadNumbersFilter(roadNumbers, includeAllPublicRoads = true)
    time(logger, "Fetch complementary data by road numbers and municipality") {
      val sql = s"""SELECT * FROM complementary_link_table WHERE municipalitycode = $municipality AND """ + roadNumberFilters
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
      val sql = s"SELECT * FROM complementary_link_table WHERE $geometry " + municipalityFilter + filter.getOrElse("")
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

  def fetchByMunicipalityF(municipality: Int): Future[Seq[RoadLink]] = {
    Future(queryByMunicipality(municipality))
  }

}
