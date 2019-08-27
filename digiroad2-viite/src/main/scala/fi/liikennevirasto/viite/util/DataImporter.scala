package fi.liikennevirasto.viite.util

import java.util.Properties

import javax.sql.DataSource
import com.jolbox.bonecp.{BoneCPConfig, BoneCPDataSource}
import org.joda.time.format.{ISODateTimeFormat, PeriodFormat}
import slick.driver.JdbcDriver.backend.{Database, DatabaseDef}
import Database.dynamicSession
import _root_.oracle.sql.STRUCT
import fi.liikennevirasto.digiroad2.asset.SideCode
import fi.liikennevirasto.digiroad2.client.vvh.VVHClient
import fi.liikennevirasto.digiroad2.dao.SequenceResetterDAO
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.{DummyEventBus, DummySerializer, GeometryUtils, Point}
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite._
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
      val cfg = new BoneCPConfig(OracleDatabase.loadProperties("/import.bonecp.properties"))
      new BoneCPDataSource(cfg)
    }

    def database() = Database.forDataSource(dataSource)
  }

  case object Conversion extends ImportDataSet {
    lazy val dataSource: DataSource = {
      val cfg = new BoneCPConfig(OracleDatabase.loadProperties("/conversion.bonecp.properties"))
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

  case class RoadTypeChangePoints(roadNumber: Long, roadPartNumber: Long, addrM: Long, before: RoadType, after: RoadType, elyCode: Long)

  /**
    * Get road type for road address object with a list of road type change points
    *
    * @param changePoints Road part change points for road types
    * @param roadAddress Road address to get the road type for
    * @return road type for the road address or if a split is needed then a split point (address) and road types for first and second split
    */
  def roadType(changePoints: Seq[RoadTypeChangePoints], roadAddress: RoadAddress): Either[RoadType, (Long, RoadType, RoadType)] = {
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

      println(s"${DateTime.now()} - Old address data removed")

      val roadAddressImporter = getRoadAddressImporter(conversionDatabase, vvhClient, importOptions)
      roadAddressImporter.importRoadAddress()

      println(s"${DateTime.now()} - Updating geometry adjustment timestamp to ${importOptions.geometryAdjustedTimeStamp}")
      sqlu"""UPDATE LINK
        SET ADJUSTED_TIMESTAMP = ${importOptions.geometryAdjustedTimeStamp}""".execute
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

  def importNodesAndJunctions(conversionDatabase: DatabaseDef) = {
    withDynTransaction{
      sqlu"""DELETE FROM JUNCTION_POINT""".execute
      sqlu"""DELETE FROM NODE_POINT""".execute
      sqlu"""DELETE FROM JUNCTION""".execute
      sqlu"""DELETE FROM NODE""".execute

      println(s"${DateTime.now()} - Old nodes and junctions data removed")
      val nodeImporter = getNodeImporter(conversionDatabase)
      nodeImporter.importNodes()
      val junctionImporter = getJunctionImporter(conversionDatabase)
      junctionImporter.importJunctions()
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
  def splitRoadAddresses(roadAddress: RoadAddress, addrMToSplit: Long, roadTypeBefore: RoadType, roadTypeAfter: RoadType, elyCode: Long): Seq[RoadAddress] = {
    // mValue at split point on a TowardsDigitizing road address:
    val splitMValue = roadAddress.startMValue + (roadAddress.endMValue - roadAddress.startMValue) / (roadAddress.endAddrMValue - roadAddress.startAddrMValue) * (addrMToSplit - roadAddress.startAddrMValue)
    println(s"Splitting roadway id = ${roadAddress.id}, tie = ${roadAddress.roadNumber} and aosa = ${roadAddress.roadPartNumber}, on AddrMValue = $addrMToSplit")
    val roadAddressA = roadAddress.copy(id = fi.liikennevirasto.viite.NewIdValue, roadType = roadTypeBefore, endAddrMValue = addrMToSplit, startMValue = if (roadAddress.sideCode == SideCode.AgainstDigitizing)
            roadAddress.endMValue - splitMValue
          else
            0.0, endMValue = if (roadAddress.sideCode == SideCode.AgainstDigitizing)
            roadAddress.endMValue
          else
            splitMValue, geometry = GeometryUtils.truncateGeometry2D(roadAddress.geometry, 0.0, splitMValue), ely = elyCode) // TODO Check roadway_number

    val roadAddressB = roadAddress.copy(id = fi.liikennevirasto.viite.NewIdValue, roadType = roadTypeAfter, startAddrMValue = addrMToSplit, startMValue = if (roadAddress.sideCode == SideCode.AgainstDigitizing)
            0.0
          else
            splitMValue, endMValue = if (roadAddress.sideCode == SideCode.AgainstDigitizing)
            roadAddress.endMValue - splitMValue
          else
            roadAddress.endMValue, geometry = GeometryUtils.truncateGeometry2D(roadAddress.geometry, splitMValue, roadAddress.endMValue), ely = elyCode) // TODO Check roadway_number
    Seq(roadAddressA, roadAddressB)
  }

  // TODO This is not used and probably should be removed.
  def updateRoadWithSingleRoadType(roadNumber:Long, roadPartNumber: Long, roadType : Long, elyCode :Long) = {
    println(s"Updating road number $roadNumber and part $roadPartNumber with roadType = $roadType and elyCode = $elyCode")
    sqlu"""UPDATE ROADWAY SET ROAD_TYPE = $roadType, ELY= $elyCode where ROAD_NUMBER = $roadNumber AND ROAD_PART_NUMBER = $roadPartNumber """.execute
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


  def updateLinearLocationGeometry(vvhClient: VVHClient, customFilter: String = ""): Unit = {
    val eventBus = new DummyEventBus
    val linearLocationDAO = new LinearLocationDAO
    val linkService = new RoadLinkService(vvhClient, eventBus, new DummySerializer)
    var changed = 0
    withLinkIdChunks {
      case (min, max) =>
        withDynTransaction {
        val linkIds = linearLocationDAO.fetchLinkIdsInChunk(min, max).toSet
        val roadLinksFromVVH = linkService.getCurrentAndComplementaryAndSuravageRoadLinksFromVVH(linkIds)
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

  private[this] def initDataSource: DataSource = {
    Class.forName("oracle.jdbc.driver.OracleDriver")
    val cfg = new BoneCPConfig(localProperties)
    new BoneCPDataSource(cfg)
  }

  lazy val localProperties: Properties = {
    val props = new Properties()
    try {
      props.load(getClass.getResourceAsStream("/bonecp.properties"))
    } catch {
      case e: Exception => throw new RuntimeException("Can't load local.properties for env: " + System.getProperty("env"), e)
    }
    props
  }

}

case class ImportOptions(onlyComplementaryLinks: Boolean, useFrozenLinkService: Boolean, geometryAdjustedTimeStamp: Long, conversionTable: String, onlyCurrentRoads: Boolean)
case class RoadPart(roadNumber: Long, roadPart: Long, ely: Long)

