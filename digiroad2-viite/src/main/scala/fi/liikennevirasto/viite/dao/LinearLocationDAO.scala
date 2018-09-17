package fi.liikennevirasto.viite.dao

import java.sql.Timestamp

import fi.liikennevirasto.digiroad2.asset.SideCode.AgainstDigitizing
import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.{Queries, Sequences}
import fi.liikennevirasto.digiroad2.oracle.MassQuery
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.process.RoadAddressFiller.LinearLocationAdjustment
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import org.slf4j.LoggerFactory
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{StaticQuery => Q}

sealed trait FloatingReason {
  def value: Int

  def isFloating: Boolean = value != 0
}

object FloatingReason {
  val values = Set(NoFloating, ApplyChanges, GeometryChanged, NewAddressGiven, GapInGeometry, ManualFloating)

  def apply(intValue: Long): FloatingReason = {
    values.find(_.value == intValue).getOrElse(NoFloating)
  }

  case object NoFloating extends FloatingReason {
    def value = 0
  }

  case object ApplyChanges extends FloatingReason {
    def value = 1
  }

  case object GeometryChanged extends FloatingReason {
    def value = 2
  }

  case object NewAddressGiven extends FloatingReason {
    def value = 3
  }

  case object GapInGeometry extends FloatingReason {
    def value = 4
  }

  case object ManualFloating extends FloatingReason {
    def value = 5
  }

  case object SplittingTool extends FloatingReason {
    def value = 6
  }

  case object ProjectToRoadAddress extends FloatingReason {
    def value = 7
  }

}

trait BaseLinearLocation {
  def id: Long

  def orderNumber: Long

  def linkId: Long

  def startMValue: Double

  def endMValue: Double

  def sideCode: SideCode

  def adjustedTimestamp: Long

  def calibrationPoints: (Option[Long], Option[Long])

  def floating: FloatingReason

  def geometry: Seq[Point]

  def linkGeomSource: LinkGeomSource

  def roadwayId: Long

  def validFrom: Option[DateTime]

  def validTo: Option[DateTime]

  def isFloating: Boolean = floating.isFloating

  def copyWithGeometry(newGeometry: Seq[Point]): BaseLinearLocation

  def getCalibrationCode: CalibrationCode = {
    calibrationPoints match {
      case (Some(_), Some(_)) => CalibrationCode.AtBoth
      case (Some(_), _) => CalibrationCode.AtBeginning
      case (_, Some(_)) => CalibrationCode.AtEnd
      case _ => CalibrationCode.No
    }
  }

  def hasCalibrationPointAt(calibrationCode: CalibrationCode): Boolean = {
    val raCalibrationCode = getCalibrationCode
    if (calibrationCode == CalibrationCode.No || calibrationCode == CalibrationCode.AtBoth)
      raCalibrationCode == calibrationCode
    else
      raCalibrationCode == CalibrationCode.AtBoth || raCalibrationCode == calibrationCode
  }

  def connected(ra2: BaseLinearLocation): Boolean = {
    val currEndPoint = sideCode match {
      case AgainstDigitizing => geometry.head
      case _ => geometry.last
    }

    val nextStartPoint = ra2.sideCode match {
      case AgainstDigitizing => ra2.geometry.last
      case _ => ra2.geometry.head
    }

    GeometryUtils.areAdjacent(nextStartPoint, currEndPoint, fi.liikennevirasto.viite.MaxDistanceForConnectedLinks)
  }
}

// Note: Geometry on linear location is not directed: it isn't guaranteed to have a direction of digitization or road addressing
case class LinearLocation(id: Long, orderNumber: Long, linkId: Long, startMValue: Double, endMValue: Double, sideCode: SideCode,
                          adjustedTimestamp: Long, calibrationPoints: (Option[Long], Option[Long]) = (None, None),
                          floating: FloatingReason = NoFloating, geometry: Seq[Point], linkGeomSource: LinkGeomSource,
                          roadwayId: Long, validFrom: Option[DateTime] = None, validTo: Option[DateTime] = None,
                          blackUnderline: Boolean = false) extends BaseLinearLocation {

  val startCalibrationPoint: Option[Long] = calibrationPoints._1
  val endCalibrationPoint: Option[Long] = calibrationPoints._2

  def isExpire(): Boolean = {
    validFrom.getOrElse(throw new IllegalStateException("The valid from should be set before call isExpire method")).isAfterNow ||
      validTo.exists(vt => vt.isEqualNow || vt.isBeforeNow)
  }

  def copyWithGeometry(newGeometry: Seq[Point]) = {
    this.copy(geometry = newGeometry)
  }

  // TODO
  /*  def toProjectLinkCalibrationPoints(): (Option[ProjectLinkCalibrationPoint], Option[ProjectLinkCalibrationPoint]) = {
      val calibrationPointSource = if (id == noRoadAddressId || id == NewRoadAddress) ProjectLinkSource else RoadAddressSource
      calibrationPoints match {
        case (None, None) => (Option.empty[ProjectLinkCalibrationPoint], Option.empty[ProjectLinkCalibrationPoint])
        case (None, Some(cp1)) => (Option.empty[ProjectLinkCalibrationPoint], Option(ProjectLinkCalibrationPoint(cp1.linkId, cp1.segmentMValue, cp1.addressMValue, calibrationPointSource)))
        case (Some(cp1), None) => (Option(ProjectLinkCalibrationPoint(cp1.linkId, cp1.segmentMValue, cp1.addressMValue, calibrationPointSource)) , Option.empty[ProjectLinkCalibrationPoint])
        case (Some(cp1), Some(cp2)) => (Option(ProjectLinkCalibrationPoint(cp1.linkId, cp1.segmentMValue, cp1.addressMValue, calibrationPointSource)), Option(ProjectLinkCalibrationPoint(cp2.linkId, cp2.segmentMValue, cp2.addressMValue, calibrationPointSource)))
      }
    }*/
}

object LinearLocationDAO {

  private def logger = LoggerFactory.getLogger(getClass)

  val formatter: DateTimeFormatter = ISODateTimeFormat.dateOptionalTimeParser()

  def dateTimeParse(string: String): DateTime = {
    formatter.parseDateTime(string)
  }

  val dateFormatter: DateTimeFormatter = ISODateTimeFormat.basicDate()

  val selectFromLinearLocation =
    """
    select loc.id, loc.roadway_id, loc.order_number, loc.link_id, loc.start_measure, loc.end_measure, loc.side_code,
      loc.cal_start_m, loc.cal_end_m, loc.link_source, loc.adjusted_timestamp, loc.floating, t.X, t.Y, t2.X, t2.Y,
      loc.valid_from, loc.valid_to
    from LINEAR_LOCATION loc cross join
      TABLE(SDO_UTIL.GETVERTICES(loc.geometry)) t cross join
      TABLE(SDO_UTIL.GETVERTICES(loc.geometry)) t2
    """

  def optDateTimeParse(string: String): Option[DateTime] = {
    try {
      if (string == null || string == "")
        None
      else
        Some(DateTime.parse(string, formatter))
    } catch {
      case ex: Exception => None
    }
  }

  def fetchLinkIdsInChunk(min: Long, max: Long): List[Long] = {
    sql"""
         select distinct(lrm.link_id)
        from linear_location lrm where lrm.link_id between $min and $max order by lrm.link_id asc
      """.as[Long].list
  }

  def fetchByLinkId(linkIds: Set[Long], includeFloating: Boolean = false, filterIds: Set[Long] = Set()): List[LinearLocation] = {
    time(logger, "Fetch linear locations by link id") {
      if (linkIds.isEmpty) {
        return List()
      }
      if (linkIds.size > 1000 || filterIds.size > 1000) {
        return fetchByLinkIdMassQuery(linkIds, includeFloating).filterNot(ra => filterIds.contains(ra.id))
      }
      val linkIdsString = linkIds.mkString(", ")
      val floating = if (!includeFloating)
        "AND loc.floating = 0"
      else
        ""
      val idFilter = if (filterIds.nonEmpty)
        s"AND loc.id not in ${filterIds.mkString("(", ", ", ")")}"
      else
        ""
      val query =
        s"""
        $selectFromLinearLocation
        where loc.link_id in ($linkIdsString) $floating $idFilter and t.id < t2.id and loc.valid_to is null
        """
      queryList(query)
    }
  }

  private def queryList(query: String): List[LinearLocation] = {
    Q.queryNA[LinearLocation](query).list.groupBy(_.id).map {
      case (_, list) =>
        list.head
    }.toList
  }

  def fetchByLinkIdMassQuery(linkIds: Set[Long], includeFloating: Boolean = false): List[LinearLocation] = {
    time(logger, "Fetch linear locations by link id - mass query") {
      MassQuery.withIds(linkIds) {
        idTableName =>
          val floating = if (!includeFloating)
            "AND loc.floating = 0"
          else
            ""
          val query =
            s"""
              $selectFromLinearLocation
              join $idTableName i on i.id = loc.link_id
              where t.id < t2.id $floating and loc.valid_to is null
            """
          queryList(query)
      }
    }
  }

  def fetchById(id: Long): Option[LinearLocation] = {
    time(logger, "Fetch linear location by id") {
      val query =
        s"""
          $selectFromLinearLocation
          where loc.id = $id and t.id < t2.id
        """
      Q.queryNA[LinearLocation](query).firstOption
    }
  }

  def fetchByIdMassQuery(ids: Set[Long], includeFloating: Boolean = false): List[LinearLocation] = {
    time(logger, "Fetch linear locations by id - mass query") {
      MassQuery.withIds(ids) {
        idTableName =>
          val floating = if (!includeFloating)
            "AND loc.floating = 0"
          else
            ""
          val query =
            s"""
              $selectFromLinearLocation
              join $idTableName i on i.id = loc.id
              where t.id < t2.id $floating and loc.valid_to is null
            """
          queryList(query)
      }
    }
  }

  def fetchAllFloatingLinearLocations: List[LinearLocation] = {
    time(logger, "Fetch all floating linear locations") {
      val query =
        s"""
          $selectFromLinearLocation
          where t.id < t2.id and loc.floating > 0 and loc.valid_to is null
          order by loc.roadway_id, loc.order_number
        """
      queryList(query)
    }
  }

  def toTimeStamp(dateTime: Option[DateTime]): Option[Timestamp] = {
    dateTime.map(dt => new Timestamp(dt.getMillis))
  }

  def expireById(ids: Set[Long]): Int = {
    val query =
      s"""
        Update LINEAR_LOCATION Set valid_to = sysdate where valid_to IS NULL and id in (${ids.mkString(", ")})
      """
    if (ids.isEmpty)
      0
    else
      Q.updateNA(query).first
  }

  def expireByLinkId(linkIds: Set[Long]): Int = {
    val query =
      s"""
        Update LINEAR_LOCATION Set valid_to = sysdate Where valid_to IS NULL and link_id in (${linkIds.mkString(", ")})
      """
    if (linkIds.isEmpty)
      0
    else
      Q.updateNA(query).first
  }

  /*
  def setLinearLocationFloatingReason(id: Long, geometry: Option[Seq[Point]], floatingReason: FloatingReason): Unit = {

    // Expire old row
    val expired = fetchById(id).getOrElse(throw new IllegalStateException(s"""Failed to set linear location $id floating reason. Linear location not found."""))
    expireById(Set(id))

    // Create new row
    if (geometry.nonEmpty) {
      create()

      val first = geometry.get.head
      val last = geometry.get.last
      val (x1, y1, z1, x2, y2, z2) = (first.x, first.y, first.z, last.x, last.y, last.z)
      val length = GeometryUtils.geometryLength(geometry.get)
      sqlu"""
           Update road_address Set floating = ${floatingReason.value},
                  geometry = MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1), MDSYS.SDO_ORDINATE_ARRAY(
                  $x1,$y1,$z1,0.0,$x2,$y2,$z2,$length))
             Where id = $id
      """.execute
    } else {
      sqlu"""
           Update road_address Set floating = ${floatingReason.value}
             Where id = $id
      """.execute
    }

  }*/

  /**
    * Marks the road address identified by the supplied Id as eiher floating or not and also updates the history of
    * those who shares the same link_id and common_history_id
    *
    * @param roadAddressId The Id of a road addresss
    */
  def changeRoadAddressFloatingWithHistory(roadAddressId: Long, geometry: Option[Seq[Point]], floatingReason: FloatingReason): Unit = {
    if (geometry.nonEmpty) {
      val first = geometry.get.head
      val last = geometry.get.last
      val (x1, y1, z1, x2, y2, z2) = (first.x, first.y, first.z, last.x, last.y, last.z)
      val length = GeometryUtils.geometryLength(geometry.get)
      sqlu"""
           Update road_address Set floating = ${floatingReason.value},
                  geometry= MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1), MDSYS.SDO_ORDINATE_ARRAY(
                  $x1,$y1,$z1,0.0,$x2,$y2,$z2,$length))
             Where id = $roadAddressId
      """.execute
    }
    sqlu"""
       update road_address set floating = ${floatingReason.value} where id in(
       select road_address.id from road_address where link_id =
       (select link_id from road_address where road_address.id = $roadAddressId))
        """.execute
  }

  def updateLinearLocation(linearLocationAdjustment: LinearLocationAdjustment): Unit = {
    val (startM, endM) = (linearLocationAdjustment.startMeasure, linearLocationAdjustment.endMeasure)
    (startM, endM) match {
      case (Some(s), Some(e)) =>
        sqlu"""
           UPDATE ROAD_ADDRESS
           SET start_measure = $s,
             end_measure = $e,
             link_id = ${linearLocationAdjustment.linkId},
             modified_date = sysdate
           WHERE id = ${linearLocationAdjustment.addressId}
      """.execute
      case (_, Some(e)) =>
        sqlu"""
           UPDATE ROAD_ADDRESS
           SET
             end_measure = ${linearLocationAdjustment.endMeasure.get},
             link_id = ${linearLocationAdjustment.linkId},
             modified_date = sysdate
           WHERE id = ${linearLocationAdjustment.addressId}
      """.execute
      case (Some(s), _) =>
        sqlu"""
           UPDATE ROAD_ADDRESS
           SET start_measure = ${linearLocationAdjustment.startMeasure.get},
             link_id = ${linearLocationAdjustment.linkId},
             modified_date = sysdate
           WHERE id = ${linearLocationAdjustment.addressId}
      """.execute
      case _ =>
    }
  }

  def updateLinkSource(id: Long, linkSource: LinkGeomSource): Boolean = {
    sqlu"""
           UPDATE LINEAR_LOCATION SET link_source = ${linkSource.value} WHERE id = $id
      """.execute
    true
  }

  // TODO Expire current row and add new row. Update should be used only in setting the valid_to -date.
  def updateGeometry(lrmId: Long, geometry: Seq[Point]): Unit = {
    if (geometry.nonEmpty) {
      val first = geometry.head
      val last = geometry.last
      val (x1, y1, z1, x2, y2, z2) = (
        GeometryUtils.scaleToThreeDigits(first.x),
        GeometryUtils.scaleToThreeDigits(first.y),
        GeometryUtils.scaleToThreeDigits(first.z),
        GeometryUtils.scaleToThreeDigits(last.x),
        GeometryUtils.scaleToThreeDigits(last.y),
        GeometryUtils.scaleToThreeDigits(last.z)
      )
      val length = GeometryUtils.geometryLength(geometry)
      sqlu"""UPDATE Linear_location
        SET geometry = MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1),
             MDSYS.SDO_ORDINATE_ARRAY($x1, $y1, $z1, 0.0, $x2, $y2, $z2, $length))
        WHERE id = ${lrmId}""".execute
    }
  }

  /**
    * Create the value for geometry field, using the updateSQL above.
    *
    * @param geometry Geometry, if available
    * @return
    */
  private def geometryToSQL(geometry: Option[Seq[Point]]) = {
    geometry match {
      case Some(geom) if geom.nonEmpty =>
      case _ => ""
    }
  }

  def getNextLinearLocationId: Long = {
    Queries.nextLinearLocationId.as[Long].first
  }

  def queryFloatingByLinkIdMassQuery(linkIds: Set[Long]): List[LinearLocation] = {
    time(logger, "Fetch floating linear locations by link id - mass query") {
      MassQuery.withIds(linkIds) {
        idTableName =>
          val query =
            s"""
              $selectFromLinearLocation
              join $idTableName i on i.id = loc.link_id
              where loc.floating > 0 and t.id < t2.id and loc.valid_to is null
            """
          queryList(query)
      }
    }
  }

  def queryFloatingByLinkId(linkIds: Set[Long]): List[LinearLocation] = {
    time(logger, "Fetch floating linear locations by link ids") {
      if (linkIds.isEmpty) {
        return List()
      }
      if (linkIds.size > 1000) {
        return queryFloatingByLinkIdMassQuery(linkIds)
      }
      val linkIdString = linkIds.mkString(", ")
      val where = s""" where loc.link_id in ($linkIdString)"""
      val query =
        s"""
          $selectFromLinearLocation
          $where AND loc.floating > 0 and t.id < t2.id and loc.valid_to is null
        """
      queryList(query)
    }
  }

  def queryById(ids: Set[Long], rejectInvalids: Boolean = true): List[LinearLocation] = {
    time(logger, "Fetch linear locations by ids") {
      if (ids.isEmpty) {
        return List()
      }
      if (ids.size > 1000) {
        return queryByIdMassQuery(ids)
      }
      val idString = ids.mkString(", ")
      val where = s""" where loc.id in ($idString)"""

      val validToFilter = if (rejectInvalids)
        " and loc.valid_to is null"
      else
        ""

      val query =
        s"""
          $selectFromLinearLocation
          $where and t.id < t2.id $validToFilter
        """
      queryList(query)
    }
  }

  def queryByIdMassQuery(ids: Set[Long]): List[LinearLocation] = {
    time(logger, "Fetch linear locations by ids - mass query") {
      MassQuery.withIds(ids) {
        idTableName =>
          val query =
            s"""
              $selectFromLinearLocation
              join $idTableName i on i.id = loc.id
              where t.id < t2.id and loc.valid_to is null
            """
          queryList(query)
      }
    }
  }

  /**
    * Remove Road Addresses (mark them as removed). Don't use more than 1000 road addresses at once.
    *
    * @param roadAddresses Seq[RoadAddress]
    * @return Number of updated rows
    */
  def remove(roadAddresses: Seq[RoadAddress]): Int = {
    val idString = roadAddresses.map(_.id).mkString(",")
    val query =
      s"""
          UPDATE LINEAR_LOCATION SET VALID_TO = sysdate WHERE id IN ($idString)
        """
    Q.updateNA(query).first
  }

  def create(linearLocations: Iterable[LinearLocation], createdBy: String): Seq[Long] = {
    val ps = dynamicSession.prepareStatement(
      """insert into LINEAR_LOCATION (id, roadway_id, order_number, link_id, start_measure, end_measure, side_code,
        cal_start_m, cal_end_m, link_source, adjusted_timestamp, floating, geometry, valid_from, valid_to, created_by)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
        MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1), MDSYS.SDO_ORDINATE_ARRAY(?,?,0.0,?,?,?,0.0,?)),
        TO_DATE(?, 'YYYY-MM-DD'), TO_DATE(?, 'YYYY-MM-DD'), ?)""")
    val (ready, idLess) = linearLocations.partition(_.id != NewLinearLocation)
    val plIds = Sequences.fetchLinearLocationIds(idLess.size)
    val createLinearLocations = ready ++ idLess.zip(plIds).map(x =>
      x._1.copy(id = x._2)
    )
    val savedIds = createLinearLocations.foreach { case (location) =>
      val id = if (location.id == NewLinearLocation) {
        Sequences.nextLinearLocationId
      } else {
        location.id
      }
      val roadwayId = if (location.roadwayId == NewRoadwayId) {
        Sequences.nextRoadwaySeqValue
      } else {
        location.roadwayId
      }
      ps.setLong(1, id)
      ps.setLong(2, roadwayId)
      ps.setLong(3, location.orderNumber)
      ps.setLong(4, location.linkId)
      ps.setDouble(5, location.startMValue)
      ps.setDouble(6, location.endMValue)
      ps.setInt(7, location.sideCode.value)
      ps.setLong(8, location.startCalibrationPoint match {
        case Some(value) => value
        case None => null
      })
      ps.setLong(9, location.endCalibrationPoint match {
        case Some(value) => value
        case None => null
      })
      ps.setInt(10, location.linkGeomSource.value)
      ps.setLong(11, location.adjustedTimestamp)
      ps.setInt(12, location.floating.value)
      val (p1, p2) = (location.geometry.head, location.geometry.last)
      ps.setDouble(13, p1.x)
      ps.setDouble(14, p1.y)
      ps.setDouble(15, location.startMValue)
      ps.setDouble(16, p2.x)
      ps.setDouble(17, p2.y)
      ps.setDouble(18, location.endMValue)
      ps.setString(19, location.validFrom match {
        case Some(dt) => dateFormatter.print(dt)
        case None => ""
      })
      ps.setString(20, location.validTo match {
        case Some(dt) => dateFormatter.print(dt)
        case None => ""
      })
      ps.setString(21, if (createdBy == null) "-" else createdBy)
      ps.addBatch()
    }
    ps.executeBatch()
    ps.close()
    createLinearLocations.map(_.id).toSeq
  }

  def lockLinearLocationWriting: Unit = {
    sqlu"""LOCK TABLE linear_location IN SHARE MODE""".execute
  }

  def getRoadwayIdsFromLinearLocation: Seq[Long] = {
    sql"""
         select distinct(loc.roadway_id)
        from linear_location loc order by loc.roadway_id asc
      """.as[Long].list
  }

  def getLinearLocationsByFilter(queryFilter: String => String): Seq[LinearLocation] = {
    time(logger, "Get linear_locations by filter") {
      val query =
        s"""
          $selectFromLinearLocation
        """
      queryList(queryFilter(query))
    }
  }

  def withLinkIdAndMeasure(linkId: Long, startM: Option[Double], endM: Option[Double])(query: String): String = {
    val startFilter = startM match {
      case Some(s) => s" AND loc.start_Measure <= $s"
      case None => ""
    }
    val endFilter = endM match {
      case Some(e) => s" AND loc.end_Measure >= $endM"
      case None => ""
    }

    query + s" WHERE loc.link_id = $linkId $startFilter $endFilter AND floating = 0" + withValidityCheck
  }

  def withRoadwayIds(fromRoadwayId: Long, toRoadwayId: Long)(query: String): String = {
    query + s" WHERE loc.roadway_id >= $fromRoadwayId AND loc.roadway_id <= $toRoadwayId"
  }

  def withValidityCheck(): String = {
    s" AND loc.valid_to IS NULL "
  }

}
