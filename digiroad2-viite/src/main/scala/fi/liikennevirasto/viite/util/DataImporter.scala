package fi.liikennevirasto.viite.util

import com.jolbox.bonecp.{BoneCPConfig, BoneCPDataSource}
import javax.sql.DataSource
import org.joda.time.format.{ISODateTimeFormat, PeriodFormat}
import slick.driver.JdbcDriver.backend.{Database, DatabaseDef}
import Database.dynamicSession
import _root_.oracle.sql.STRUCT
import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.asset.{AdministrativeClass, SideCode}
import fi.liikennevirasto.digiroad2.client.vvh.VVHClient
import fi.liikennevirasto.digiroad2.dao.SequenceResetterDAO
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.ViiteProperties
import fi.liikennevirasto.digiroad2.{DummyEventBus, DummySerializer, Point}
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao._
import org.joda.time.{DateTime, _}
import org.slf4j.LoggerFactory
import slick.driver.JdbcDriver.backend.Database
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc._

object DataImporter {
  sealed trait ImportDataSet {
    def database(): DatabaseDef
  }

  case object TemporaryTables extends ImportDataSet {
    lazy val dataSource: DataSource = {
      val cfg = new BoneCPConfig(ViiteProperties.importBonecpProperties)
      new BoneCPDataSource(cfg)
    }

    def database() = Database.forDataSource(dataSource)
  }

  case object Conversion extends ImportDataSet {
    lazy val dataSource: DataSource = {
      val cfg = new BoneCPConfig(ViiteProperties.conversionBonecpProperties)
      new BoneCPDataSource(cfg)
    }

    def database() = Database.forDataSource(dataSource)
    val roadLinkTable: String = "tielinkki"
    val busStopTable: String = "lineaarilokaatio"
  }

  def humanReadableDurationSince(startTime: DateTime): String = {
    PeriodFormat.getDefault.print(new Period(startTime, DateTime.now()))
  }
}

class DataImporter {
  val logger = LoggerFactory.getLogger(getClass)
  lazy val ds: DataSource = initDataSource

  val Modifier = "dr1conversion"

  def withDynTransaction(f: => Unit): Unit = OracleDatabase.withDynTransaction(f)
  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)
  def withLinkIdChunks(f: (Long, Long) => Unit): Unit = {
    val chunks = withDynSession{ fetchChunkLinkIds()}
    chunks.par.foreach { p => f(p._1, p._2) }
  }

  implicit object SetStruct extends SetParameter[STRUCT] {
    def apply(v: STRUCT, pp: PositionedParameters) {
      pp.setObject(v, java.sql.Types.STRUCT)
    }
  }

  def time[A](f: => A) = {
    val s = System.nanoTime
    val ret = f
    println("time for insert " + (System.nanoTime - s) / 1e6 + "ms")
    ret
  }

  val dateFormatter = ISODateTimeFormat.basicDate()

  def getBatchDrivers(n: Int, m: Int, step: Int): List[(Int, Int)] = {
    if ((m - n) < step) {
      List((n, m))
    } else {
      val x = (n to m by step).sliding(2).map(x => (x(0), x(1) - 1)).toList
      x :+ (x.last._2 + 1, m)
    }
  }

  case class AdministrativeClassChangePoints(roadNumber: Long, roadPartNumber: Long, addrM: Long, before: AdministrativeClass, after: AdministrativeClass, elyCode: Long)

  /**
    * Get administrative class for road address object with a list of road type change points
    *
    * @param changePoints Road part change points for road types
    * @param roadAddress Road address to get the dministrative class for
    * @return dministrative class for the road address or if a split is needed then a split point (address) and road types for first and second split
    */
  def getAdministrativeClass(changePoints: Seq[AdministrativeClassChangePoints], roadAddress: RoadAddress): Either[AdministrativeClass, (Long, AdministrativeClass, AdministrativeClass)] = {
    // Check if this road address overlaps the change point and needs to be split
    val overlaps = changePoints.find(c => c.addrM > roadAddress.startAddrMValue && c.addrM < roadAddress.endAddrMValue)
    if (overlaps.nonEmpty)
      Right((overlaps.get.addrM, overlaps.get.before, overlaps.get.after))
    else {
      // There is no overlap, check if this road address is between [0, min(addrM))
      if (roadAddress.startAddrMValue < changePoints.map(_.addrM).min) {
        Left(changePoints.minBy(_.addrM).before)
      } else {
        Left(changePoints.filter(_.addrM <= roadAddress.startAddrMValue).maxBy(_.addrM).after)
      }
    }

  }

  def importRoadAddressData(conversionDatabase: DatabaseDef, vvhClient: VVHClient,
                            importOptions: ImportOptions): Unit = {

    withDynTransaction {
      disableRoadwayTriggers
      sqlu"""DELETE FROM PROJECT_LINK_NAME""".execute
      sqlu"""DELETE FROM ROADWAY_CHANGES_LINK""".execute
      sqlu"""DELETE FROM PROJECT_LINK""".execute
      sqlu"""DELETE FROM PROJECT_LINK_HISTORY""".execute
      sqlu"""DELETE FROM PROJECT_RESERVED_ROAD_PART""".execute
      sqlu"""DELETE FROM PROJECT WHERE STATE != 5""".execute
      sqlu"""DELETE FROM ROADWAY_CHANGES WHERE project_id NOT IN (SELECT id FROM PROJECT)""".execute
      sqlu"""DELETE FROM ROAD_NETWORK_ERROR""".execute
      sqlu"""DELETE FROM PUBLISHED_ROADWAY""".execute
      sqlu"""DELETE FROM PUBLISHED_ROAD_NETWORK""".execute
      sqlu"""DELETE FROM LINEAR_LOCATION""".execute
      sqlu"""DELETE FROM CALIBRATION_POINT""".execute
      sqlu"""DELETE FROM JUNCTION_POINT""".execute
      sqlu"""DELETE FROM NODE_POINT""".execute
      sqlu"""DELETE FROM ROADWAY_POINT""".execute
      sqlu"""DELETE FROM JUNCTION""".execute
      sqlu"""DELETE FROM NODE""".execute
      sqlu"""DELETE FROM LINK""".execute
      sqlu"""DELETE FROM ROADWAY""".execute
      resetRoadAddressSequences()

      println(s"${DateTime.now()} - Old address data removed")

      val roadAddressImporter = getRoadAddressImporter(conversionDatabase, vvhClient, importOptions)
      roadAddressImporter.importRoadAddress()

      println(s"${DateTime.now()} - Updating terminated roadways information")
      sqlu"""UPDATE ROADWAY SET TERMINATED = 2
            WHERE TERMINATED = 0 AND end_date IS NOT null AND EXISTS (SELECT 1 FROM ROADWAY rw
            	WHERE ROADWAY.ROAD_NUMBER = rw.ROAD_NUMBER
            	AND ROADWAY.ROADWAY_NUMBER = rw.ROADWAY_NUMBER
            	AND ROADWAY.ROAD_PART_NUMBER = rw.ROAD_PART_NUMBER
            	AND ROADWAY.START_ADDR_M = rw.START_ADDR_M
            	AND ROADWAY.END_ADDR_M = rw.END_ADDR_M
            	AND ROADWAY.TRACK = rw.TRACK
            	AND ROADWAY.END_DATE = rw.start_date - 1
            	AND rw.VALID_TO IS NULL AND rw.TERMINATED = 1)""".execute

      enableRoadwayTriggers
      roadwayResetter()
    }
  }

  def resetRoadAddressSequences() = {
    val sequenceResetter = new SequenceResetterDAO()
    sequenceResetter.resetSequenceToNumber("PROJECT_LINK_NAME_SEQ", 1)
    sequenceResetter.resetSequenceToNumber("ROADWAY_CHANGE_LINK", 1)
    sequenceResetter.resetSequenceToNumber("ROAD_NETWORK_ERROR_SEQ", 1)
    sequenceResetter.resetSequenceToNumber("PUBLISHED_ROAD_NETWORK_SEQ", 1)
    sequenceResetter.resetSequenceToNumber("LINEAR_LOCATION_SEQ", 1)
    sequenceResetter.resetSequenceToNumber("CALIBRATION_POINT_SEQ", 1)
    sequenceResetter.resetSequenceToNumber("ROADWAY_POINT_SEQ", 1)
    sequenceResetter.resetSequenceToNumber("ROADWAY_SEQ", 1)
    sequenceResetter.resetSequenceToNumber("VIITE_GENERAL_SEQ", 1)
  }

  def resetNodesAndJunctionSequences(): Unit = {
    val sequenceResetter = new SequenceResetterDAO()
    sequenceResetter.resetSequenceToNumber("JUNCTION_POINT_SEQ", 1)
    sequenceResetter.resetSequenceToNumber("NODE_POINT_SEQ", 1)
    sequenceResetter.resetSequenceToNumber("JUNCTION_SEQ", 1)
    sequenceResetter.resetSequenceToNumber("NODE_SEQ", 1)
  }

  private def updateNodePointType() = {
    sqlu"""
      UPDATE NODE_POINT NP SET "TYPE" = (SELECT CASE
          -- [TYPE = 99] Includes expired node points points or points attached to expired nodes
          WHEN (point.VALID_TO IS NOT NULL OR NOT EXISTS (SELECT 1 FROM NODE node
            WHERE node.NODE_NUMBER = point.NODE_NUMBER AND (node.END_DATE IS NULL AND node.VALID_TO IS NULL))) THEN 99
          -- [TYPE = 1] Includes templates, points where ADDR_M is equal to START_ADDR_M or END_ADDR_M of the road (road_number, road_part_number and track) and when ADMINISTRATIVE_CLASS changes
          WHEN point.NODE_NUMBER IS NULL THEN 1 -- node point template
          WHEN (rp.ADDR_M = (SELECT MIN(roadAddr.START_ADDR_M) FROM ROADWAY roadAddr
            WHERE roadAddr.ROAD_NUMBER = rw.ROAD_NUMBER AND roadAddr.ROAD_PART_NUMBER = rw.ROAD_PART_NUMBER
            AND roadAddr.VALID_TO IS NULL AND roadAddr.END_DATE IS NULL)) THEN 1 -- ADDR_M is equal to START_ADDR_M
          WHEN (rp.ADDR_M = (SELECT MAX(roadAddr.END_ADDR_M) FROM ROADWAY roadAddr
            WHERE roadAddr.ROAD_NUMBER = rw.ROAD_NUMBER AND roadAddr.ROAD_PART_NUMBER = rw.ROAD_PART_NUMBER
            AND roadAddr.VALID_TO IS NULL AND roadAddr.END_DATE IS NULL)) THEN 1 -- ADDR_M is equal to END_ADDR_M
          WHEN ((SELECT DISTINCT(roadAddr.ADMINISTRATIVE_CLASS) FROM ROADWAY roadAddr
              WHERE roadAddr.ROAD_NUMBER = rw.ROAD_NUMBER AND roadAddr.ROAD_PART_NUMBER = rw.ROAD_PART_NUMBER AND roadAddr.START_ADDR_M = rp.ADDR_M
              AND roadAddr.VALID_TO IS NULL AND roadAddr.END_DATE IS NULL) !=
            (SELECT DISTINCT(roadAddr.ADMINISTRATIVE_CLASS) FROM ROADWAY roadAddr
              WHERE roadAddr.ROAD_NUMBER = rw.ROAD_NUMBER AND roadAddr.ROAD_PART_NUMBER = rw.ROAD_PART_NUMBER AND roadAddr.END_ADDR_M = rp.ADDR_M
              AND roadAddr.VALID_TO IS NULL AND roadAddr.END_DATE IS NULL)) THEN 1 -- ADMINISTRATIVE_CLASS changed on ADDR_M
          -- [TYPE = 2]
          ELSE 2
        END AS NODE_POINT_TYPE
        FROM NODE_POINT point
        LEFT JOIN ROADWAY_POINT rp ON point.ROADWAY_POINT_ID = rp.ID
        LEFT JOIN ROADWAY rw ON rp.ROADWAY_NUMBER = rw.ROADWAY_NUMBER AND rw.VALID_TO IS NULL AND rw.END_DATE IS NULL
          WHERE point.ID = NP.ID AND ROWNUM = 1)""".execute
  }

  def importNodesAndJunctions(conversionDatabase: DatabaseDef) = {
    withDynTransaction {
      sqlu"""DELETE FROM JUNCTION_POINT""".execute
      sqlu"""DELETE FROM NODE_POINT""".execute
      sqlu"""DELETE FROM JUNCTION""".execute
      sqlu"""DELETE FROM NODE""".execute
      resetNodesAndJunctionSequences()

      println(s"${DateTime.now()} - Old nodes and junctions data removed")
      val nodeImporter = getNodeImporter(conversionDatabase)
      nodeImporter.importNodes()
      val junctionImporter = getJunctionImporter(conversionDatabase)
      junctionImporter.importJunctions()
      updateNodePointType()
    }
  }

  def enableRoadwayTriggers = {
    sqlu"""ALTER TABLE ROADWAY ENABLE ALL TRIGGERS""".execute
  }

  def disableRoadwayTriggers = {
    sqlu"""ALTER TABLE ROADWAY DISABLE ALL TRIGGERS""".execute
  }

  def roadwayResetter(): Unit = {
    val sequenceResetter = new SequenceResetterDAO()
    sql"""select MAX(ROADWAY_NUMBER) FROM ROADWAY""".as[Long].firstOption match {
      case Some(roadwayNumber) =>
        sequenceResetter.resetSequenceToNumber("ROADWAY_NUMBER_SEQ", roadwayNumber + 1)
      case _ => sequenceResetter.resetSequenceToNumber("ROADWAY_NUMBER_SEQ", 1)
    }
  }

  protected def getRoadAddressImporter(conversionDatabase: DatabaseDef, vvhClient: VVHClient, importOptions: ImportOptions) = {
    new RoadAddressImporter(conversionDatabase, vvhClient, importOptions)
  }

  protected def getNodeImporter(conversionDatabase: DatabaseDef) : NodeImporter = {
    new NodeImporter(conversionDatabase)
  }

  protected def getJunctionImporter(conversionDatabase: DatabaseDef) : JunctionImporter = {
    new JunctionImporter(conversionDatabase)
  }

  // TODO This is not used and should probably be removed.
  def splitRoadAddresses(roadAddress: RoadAddress, addrMToSplit: Long, administrativeClassBefore: AdministrativeClass, administrativeClassAfter: AdministrativeClass, elyCode: Long): Seq[RoadAddress] = {
    // mValue at split point on a TowardsDigitizing road address:
    val splitMValue = roadAddress.startMValue + (roadAddress.endMValue - roadAddress.startMValue) / (roadAddress.endAddrMValue - roadAddress.startAddrMValue) * (addrMToSplit - roadAddress.startAddrMValue)
    println(s"Splitting roadway id = ${roadAddress.id}, tie = ${roadAddress.roadNumber} and aosa = ${roadAddress.roadPartNumber}, on AddrMValue = $addrMToSplit")
    val roadAddressA = roadAddress.copy(id = fi.liikennevirasto.viite.NewIdValue, administrativeClass = administrativeClassBefore, endAddrMValue = addrMToSplit, startMValue = if (roadAddress.sideCode == SideCode.AgainstDigitizing)
                roadAddress.endMValue - splitMValue
              else
                0.0, endMValue = if (roadAddress.sideCode == SideCode.AgainstDigitizing)
                roadAddress.endMValue
              else
                splitMValue, geometry = GeometryUtils.truncateGeometry2D(roadAddress.geometry, 0.0, splitMValue), ely = elyCode) // TODO Check roadway_number

    val roadAddressB = roadAddress.copy(id = fi.liikennevirasto.viite.NewIdValue, administrativeClass = administrativeClassAfter, startAddrMValue = addrMToSplit, startMValue = if (roadAddress.sideCode == SideCode.AgainstDigitizing)
                0.0
              else
                splitMValue, endMValue = if (roadAddress.sideCode == SideCode.AgainstDigitizing)
                roadAddress.endMValue - splitMValue
              else
                roadAddress.endMValue, geometry = GeometryUtils.truncateGeometry2D(roadAddress.geometry, splitMValue, roadAddress.endMValue), ely = elyCode) // TODO Check roadway_number
    Seq(roadAddressA, roadAddressB)
  }

  private def generateChunks(linkIds: Seq[Long], chunkNumber: Long): Seq[(Long, Long)] = {
    val (chunks, _) = linkIds.foldLeft((Seq[Long](0), 0)) {
      case ((fchunks, index), linkId) =>
        if (index > 0 && index % chunkNumber == 0) {
          (fchunks ++ Seq(linkId), index + 1)
        } else {
          (fchunks, index + 1)
        }
    }
    val result = if (chunks.last == linkIds.last) {
      chunks
    } else {
      chunks ++ Seq(linkIds.last)
    }

    result.zip(result.tail)
  }

  protected def fetchChunkLinkIds(): Seq[(Long, Long)] = {
    val linkIds = sql"""select distinct link_id from linear_location where link_id is not null order by link_id""".as[Long].list
    generateChunks(linkIds, 25000l)
  }


  def updateLinearLocationGeometry(vvhClient: VVHClient, geometryFrozen: Boolean): Unit = {
    val eventBus = new DummyEventBus
    val linearLocationDAO = new LinearLocationDAO
    val linkService = new RoadLinkService(vvhClient, eventBus, new DummySerializer, geometryFrozen)
    var changed = 0
    withLinkIdChunks {
      case (min, max) =>
        withDynTransaction {
          val linkIds = linearLocationDAO.fetchLinkIdsInChunk(min, max).toSet
          val roadLinksFromVVH = linkService.getCurrentAndComplementaryRoadLinksFromVVH(linkIds)
          val unGroupedTopology = linearLocationDAO.fetchByLinkId(roadLinksFromVVH.map(_.linkId).toSet)
          val topologyLocation = unGroupedTopology.groupBy(_.linkId)
          roadLinksFromVVH.foreach(roadLink => {
            val segmentsOnViiteDatabase = topologyLocation.getOrElse(roadLink.linkId, Set())
            segmentsOnViiteDatabase.foreach(segment => {
              val newGeom = GeometryUtils.truncateGeometry3D(roadLink.geometry, segment.startMValue, segment.endMValue)
              if (!segment.geometry.equals(Nil) && !newGeom.equals(Nil)) {
                val distanceFromHeadToHead = segment.geometry.head.distance2DTo(newGeom.head)
                val distanceFromHeadToLast = segment.geometry.head.distance2DTo(newGeom.last)
                val distanceFromLastToHead = segment.geometry.last.distance2DTo(newGeom.head)
                val distanceFromLastToLast = segment.geometry.last.distance2DTo(newGeom.last)
                if (((distanceFromHeadToHead > MinDistanceForGeometryUpdate) &&
                  (distanceFromHeadToLast > MinDistanceForGeometryUpdate)) ||
                  ((distanceFromLastToHead > MinDistanceForGeometryUpdate) &&
                    (distanceFromLastToLast > MinDistanceForGeometryUpdate))) {
                  updateGeometry(segment.id, newGeom)
                  println("Changed geometry on linear location id " + segment.id + " and linkId =" + segment.linkId)
                  changed += 1
                } else {
                  println(s"Skipped geometry update on linear location ID : ${segment.id} and linkId: ${segment.linkId}")
                }
              }
            })
          })
        }
    }
    println(s"Geometries changed count: $changed")
  }

  def updateGeometry(linearLocationId: Long, geometry: Seq[Point]): Unit = {
    if (geometry.nonEmpty) {
      val first = geometry.head
      val last = geometry.last
      val (x1, y1, x2, y2) = (
        GeometryUtils.scaleToThreeDigits(first.x),
        GeometryUtils.scaleToThreeDigits(first.y),
        GeometryUtils.scaleToThreeDigits(last.x),
        GeometryUtils.scaleToThreeDigits(last.y)
      )
      val length = GeometryUtils.geometryLength(geometry)
      sqlu"""UPDATE LINEAR_LOCATION
          SET geometry = MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1),
               MDSYS.SDO_ORDINATE_ARRAY($x1, $y1, 0.0, 0.0, $x2, $y2, 0.0, $length))
          WHERE id = $linearLocationId""".execute
    }
  }

  def updateCalibrationPointTypesQuery() = {
    withDynTransaction {
      val source = io.Source.fromInputStream(getClass.getClassLoader.getResourceAsStream("db/migration/V0_38__calibration_point_type_update.sql"))
      var text = try source.mkString finally source.close()
      // remove ; at end of SQL
      text = text.substring(0,text.length - 1)
      sqlu"""#$text""".execute
    }
  }

  private[this] def initDataSource: DataSource = {
    Class.forName("oracle.jdbc.driver.OracleDriver")
    val cfg = new BoneCPConfig(ViiteProperties.bonecpProperties)
    new BoneCPDataSource(cfg)
  }

}

case class ImportOptions(onlyComplementaryLinks: Boolean, useFrozenLinkService: Boolean, conversionTable: String, onlyCurrentRoads: Boolean)
case class RoadPart(roadNumber: Long, roadPart: Long, ely: Long)

