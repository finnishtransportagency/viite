package fi.liikennevirasto.viite.util

import com.jolbox.bonecp.{BoneCPConfig, BoneCPDataSource}
import javax.sql.DataSource
import slick.driver.JdbcDriver.backend.{Database, DatabaseDef}
import Database.dynamicSession
import fi.liikennevirasto.digiroad2.{DummyEventBus, DummySerializer}
import fi.liikennevirasto.digiroad2.client.kgv.KgvRoadLink
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.{SqlScriptRunner, ViiteProperties}
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.util.DataImporter.Conversion
import fi.vaylavirasto.viite.dao.{BaseDAO, SequenceResetterDAO}
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point}
import fi.vaylavirasto.viite.postgis.PostGISDatabase
import org.joda.time.DateTime
import slick.driver
import slick.driver.JdbcDriver
import slick.jdbc.StaticQuery.interpolation

object DataImporter {

  sealed trait ImportDataSet {
    def database(): DatabaseDef
  }

  case object Conversion extends ImportDataSet {
    lazy val dataSource: DataSource = {
      val cfg = new BoneCPConfig(ViiteProperties.conversionBonecpProperties)
      new BoneCPDataSource(cfg)
    }

    def database(): DatabaseDef = Database.forDataSource(dataSource)
  }
}

class DataImporter extends BaseDAO{

  private lazy val geometryFrozen: Boolean = ViiteProperties.kgvRoadlinkFrozen

  val Modifier = "dr1conversion"

  def withDynTransaction(f: => Unit): Unit = PostGISDatabase.withDynTransaction(f)
  def withDynSession[T](f: => T): T = PostGISDatabase.withDynSession(f)
  private def runUpdateToDbSlick(updateQuery: String): Int = {
    sqlu"""#$updateQuery""".buildColl.toList.head //sqlu"""#$updateQuery""".execute
  }
  def time[A](f: => A): A = {
    val s = System.nanoTime
    val ret = f
    println("time for insert " + (System.nanoTime - s) / 1e6 + "ms")
    ret
  }

  def initialImport(importTableName: Option[String]): Unit = {
    println("\nImporting road addresses, updating geometry and importing nodes and junctions started at time: ")
    println(DateTime.now())
    importRoadAddresses(importTableName)
    updateLinearLocationGeometry()
    importNodesAndJunctions()
    updateCalibrationPointTypes()
  }

  def importRoadAddresses(importTableName: Option[String]): Unit = {
    println(s"\nCommencing road address import from conversion at time: ${DateTime.now()}")
    val KGVClient = new KgvRoadLink
    importTableName match {
      case None => // shouldn't get here because args size test
        throw new Exception("****** Import failed! Conversion table name required as a second input ******")
      case Some(tableName) =>
        val importOptions = ImportOptions(
          onlyComplementaryLinks = false,
          useFrozenLinkService = geometryFrozen,
          tableName,
          onlyCurrentRoads = ViiteProperties.importOnlyCurrent)
        importRoadAddressData(Conversion.database(), KGVClient, importOptions)
        println(s"Road address import complete at time: ${DateTime.now()}")
    }
  }

  def importRoadAddressData(conversionDatabase: JdbcDriver.backend.DatabaseDef, KGVClient: KgvRoadLink, importOptions: ImportOptions): Unit = {

    println(s"\nimportRoadAddressData    started at time:  ${DateTime.now()}")
    withDynTransaction {

      println(s"\nDisabling roadway triggers started at time: ${DateTime.now()}")
      disableRoadwayTriggers
      println(s"\nDeleting old Alkulataus tables' data")
      println(s"  Deleting PROJECT_LINK_NAMEs         started at time: ${DateTime.now()}")
      runUpdateToDbSlick(s"""DELETE FROM PROJECT_LINK_NAME""")
      println(s"  Deleting ROADWAY_CHANGES              started at time: ${DateTime.now()}")
      runUpdateToDbSlick(s"""DELETE FROM ROADWAY_CHANGES_LINK""")
      println(s"  Deleting PROJECT_LINKs                  started at time: ${DateTime.now()}")
      runUpdateToDbSlick(s"""DELETE FROM PROJECT_LINK""")
      println(s"  Deleting PROJECT_INK_LHISTORY             started at time: ${DateTime.now()}")
      runUpdateToDbSlick(s"""DELETE FROM PROJECT_LINK_HISTORY""")
      println(s"  Deleting PROJECT_RESERVED_ROAD_PARTs links  started at time: ${DateTime.now()}")
      runUpdateToDbSlick(s"""DELETE FROM PROJECT_RESERVED_ROAD_PART""")


      // Delete other than accepted projects.
      // Accepted states: 0 = ProjectDAO.ProjectState.Accepted; 5 = ProjectState.DeprecatedSaved2ToTR
      println(s"  Deleting PROJECTs (state != 12|5)           started at time: ${DateTime.now()}")
      runUpdateToDbSlick(s"""DELETE FROM PROJECT WHERE STATE != 12 AND STATE != 5""")

      println(s"  Deleting ROADWAY_CHANGESs                 started at time: ${DateTime.now()}")
      runUpdateToDbSlick(s"""DELETE FROM ROADWAY_CHANGES WHERE project_id NOT IN (SELECT id FROM PROJECT)""")
      println(s"  Deleting ROAD_NETWORK_ERRORs            started at time: ${DateTime.now()}")
      runUpdateToDbSlick(s"""DELETE FROM ROAD_NETWORK_ERROR""")

      /* todo ("Table published_roadwayis no longer in use, and is empty.") */
      println(s"  Deleting PUBLISHED_ROADWAYs           started at time: ${DateTime.now()}")
      runUpdateToDbSlick(s"""DELETE FROM PUBLISHED_ROADWAY""")

      /* todo ("Table published_road_network is no longer in use, and is empty.") */
      println(s"  Deleting PUBLISHED_ROAD_NETWORKs    started at time: ${DateTime.now()}")
      runUpdateToDbSlick(s"""DELETE FROM PUBLISHED_ROAD_NETWORK""")

      println(s"  Deleting LINEAR_LOCATIONs         started at time: ${DateTime.now()}")
      runUpdateToDbSlick(s"""DELETE FROM LINEAR_LOCATION""")
      println(s"  Deleting CALIBRATION_POINTs     started at time: ${DateTime.now()}")
      runUpdateToDbSlick(s"""DELETE FROM CALIBRATION_POINT""")
      println(s"  Deleting JUNCTION_POINTs      started at time: ${DateTime.now()}")
      runUpdateToDbSlick(s"""DELETE FROM JUNCTION_POINT""")
      println(s"  Deleting NODE_POINTs        started at time: ${DateTime.now()}")
      runUpdateToDbSlick(s"""DELETE FROM NODE_POINT""")
      println(s"  Deleting ROADWAY_POINTs   started at time: ${DateTime.now()}")
      runUpdateToDbSlick(s"""DELETE FROM ROADWAY_POINT""")
      println(s"  Deleting JUNCTIONs      started at time: ${DateTime.now()}")
      runUpdateToDbSlick(s"""DELETE FROM JUNCTION""")
      println(s"  Deleting NODEs        started at time: ${DateTime.now()}")
      runUpdateToDbSlick(s"""DELETE FROM NODE""")
      println(s"  Deleting LINKs      started at time: ${DateTime.now()}")
      runUpdateToDbSlick(s"""DELETE FROM LINK""")
      println(s"  Deleting ROADWAYs started at time: ${DateTime.now()}")
      runUpdateToDbSlick(s"""DELETE FROM ROADWAY""")

      resetRoadAddressSequences()

      println(s"${DateTime.now()} - Old address data removed")
      val roadAddressImporter = getRoadAddressImporter(conversionDatabase, KGVClient, importOptions)
      roadAddressImporter.importRoadAddress()

      println(s"\n${DateTime.now()} - Updating terminated roadways information")
      runUpdateToDbSlick(s"""UPDATE ROADWAY SET TERMINATED = 2
            WHERE TERMINATED = 0 AND end_date IS NOT null AND EXISTS (SELECT 1 FROM ROADWAY rw
            	WHERE ROADWAY.ROAD_NUMBER = rw.ROAD_NUMBER
            	AND ROADWAY.ROADWAY_NUMBER = rw.ROADWAY_NUMBER
            	AND ROADWAY.ROAD_PART_NUMBER = rw.ROAD_PART_NUMBER
            	AND ROADWAY.START_ADDR_M = rw.START_ADDR_M
            	AND ROADWAY.END_ADDR_M = rw.END_ADDR_M
            	AND ROADWAY.TRACK = rw.TRACK
            	AND ROADWAY.END_DATE = rw.start_date - 1
            	AND rw.VALID_TO IS NULL AND rw.TERMINATED = 1)""")

      println(s"\nEnabling roadway triggers    started at time: ${DateTime.now()}")
      enableRoadwayTriggers
      roadwaySequenceResetter()
    }
  }

  def importRoadNames(): Unit = {
    SqlScriptRunner.runScriptInClasspath("/roadnames.sql")
  }

  def importMunicipalities(): Unit = {
    SqlScriptRunner.runScripts(List("municipalities.sql"))
  }

  def importNodesAndJunctions(): Unit = {
    importNodesAndJunctions(Conversion.database())
  }

  def importNodesAndJunctions(conversionDatabase: DatabaseDef): Unit = {
    withDynTransaction {
      println("\nImporting nodes and junctions started at time: ")
      println(DateTime.now())

      runUpdateToDbSlick(s"""DELETE FROM JUNCTION_POINT""")
      runUpdateToDbSlick(s"""DELETE FROM NODE_POINT""")
      runUpdateToDbSlick(s"""DELETE FROM JUNCTION""")
      runUpdateToDbSlick(s"""DELETE FROM NODE""")
      resetNodesAndJunctionSequences()

      println(s"${DateTime.now()} - Old nodes and junctions data removed")
      val nodeImporter = getNodeImporter(conversionDatabase)
      nodeImporter.importNodes()
      val junctionImporter = getJunctionImporter(conversionDatabase)
      junctionImporter.importJunctions()
      updateNodePointType()
    }
  }

  def resetRoadAddressSequences(): Unit = {
    println("\nResetting road related sequences started at time: ")
    println(DateTime.now())

    val sequenceResetter = new SequenceResetterDAO()
    sequenceResetter.resetSequenceToNumber("PROJECT_LINK_NAME_SEQ", 1)
    sequenceResetter.resetSequenceToNumber("ROADWAY_CHANGE_LINK", 1)
    sequenceResetter.resetSequenceToNumber("ROAD_NETWORK_ERROR_SEQ", 1)

    //@deprecated ("Table published_road_network is no longer in use, and is empty.")
    sequenceResetter.resetSequenceToNumber("PUBLISHED_ROAD_NETWORK_SEQ", 1)

    sequenceResetter.resetSequenceToNumber("LINEAR_LOCATION_SEQ", 1)
    sequenceResetter.resetSequenceToNumber("CALIBRATION_POINT_SEQ", 1)
    sequenceResetter.resetSequenceToNumber("ROADWAY_POINT_SEQ", 1)
    sequenceResetter.resetSequenceToNumber("ROADWAY_SEQ", 1)
    sequenceResetter.resetSequenceToNumber("VIITE_GENERAL_SEQ", 1)
  }

  def resetNodesAndJunctionSequences(): Unit = {
    println("\nResetting nodes & junctions related sequences started at time: ")
    println(DateTime.now())

    val sequenceResetter = new SequenceResetterDAO()
    sequenceResetter.resetSequenceToNumber("JUNCTION_POINT_SEQ", 1)
    sequenceResetter.resetSequenceToNumber("NODE_POINT_SEQ", 1)
    sequenceResetter.resetSequenceToNumber("JUNCTION_SEQ", 1)
    sequenceResetter.resetSequenceToNumber("NODE_SEQ", 1)
  }

  private def updateNodePointType(): Unit = {
    println("\nUpdating nodePointTypes started at time: ")
    println(DateTime.now())

    runUpdateToDbSlick(s"""
      UPDATE NODE_POINT NP SET TYPE = (SELECT CASE
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
          WHERE point.ID = NP.ID
          LIMIT 1)""")
  }

  def updateLinearLocationGeometry(): Unit = {
    println(s"\nUpdating road address table geometries at time: ${DateTime.now()}")
    val KGVClient = new KgvRoadLink
    updateLinearLocationGeometry(KGVClient, geometryFrozen)
    println(s"Road addresses geometry update complete at time: ${DateTime.now()}")
    println()
  }

  def enableRoadwayTriggers(): Unit = {
    runUpdateToDbSlick(s"""ALTER TABLE ROADWAY ENABLE TRIGGER USER""")
  }

  def disableRoadwayTriggers(): Unit = {
    runUpdateToDbSlick(s"""ALTER TABLE ROADWAY DISABLE TRIGGER USER""")
  }

  /** Resets the roadway sequence to (MAX-of-current-roadway-numbers)+1, or to 1, if no roadways available. */
  def roadwaySequenceResetter(): Unit = {
    println(s"\nResetting roadway related sequences started at time: ${DateTime.now()}")

    val sequenceResetter = new SequenceResetterDAO()
    sql"""select MAX(ROADWAY_NUMBER) FROM ROADWAY""".as[Long].firstOption match {
      case Some(roadwayNumber) =>
        sequenceResetter.resetSequenceToNumber("ROADWAY_NUMBER_SEQ", roadwayNumber + 1)
      case _ => sequenceResetter.resetSequenceToNumber("ROADWAY_NUMBER_SEQ", 1)
    }
  }

  protected def getRoadAddressImporter(conversionDatabase: driver.JdbcDriver.backend.DatabaseDef, KGVClient: KgvRoadLink, importOptions: ImportOptions): RoadAddressImporter = {
    new RoadAddressImporter(conversionDatabase, KGVClient, importOptions)
  }

  protected def getNodeImporter(conversionDatabase: DatabaseDef) : NodeImporter = {
    new NodeImporter(conversionDatabase)
  }

  protected def getJunctionImporter(conversionDatabase: DatabaseDef) : JunctionImporter = {
    new JunctionImporter(conversionDatabase)
  }

  protected def fetchGroupedLinkIds: Seq[Set[String]] = {
    val linkIds = sql"""select distinct link_id from linear_location where link_id is not null order by link_id""".as[String].list
    linkIds.toSet.grouped(25000).toSeq
  }

  private def updateLinearLocationGeometry(KGVClient: KgvRoadLink, geometryFrozen: Boolean): Unit = {
    val eventBus = new DummyEventBus
    val linearLocationDAO = new LinearLocationDAO
    val linkService = new RoadLinkService(KGVClient, eventBus, new DummySerializer, geometryFrozen)
    var changed = 0
    var skipped = 0 /// For log information about update-skipped linear locations, skip due to sameness to the old data
    val linkIds = withDynSession{ fetchGroupedLinkIds }
    linkIds.par.foreach {
      case linkIds =>
        withDynTransaction {
          val roadLinksFromKGV = linkService.getCurrentAndComplementaryRoadLinks(linkIds)
          val unGroupedTopology = linearLocationDAO.fetchByLinkId(roadLinksFromKGV.map(_.linkId).toSet)
          val topologyLocation = unGroupedTopology.groupBy(_.linkId)
          roadLinksFromKGV.foreach(roadLink => {
            val segmentsOnViiteDatabase = topologyLocation.getOrElse(roadLink.linkId, Set())
            segmentsOnViiteDatabase.foreach(segment => {
              val newGeom = GeometryUtils.truncateGeometry3D(roadLink.geometry, segment.startMValue, segment.endMValue)
              if (!segment.geometry.equals(Nil) && !newGeom.equals(Nil)) {
                if(skipped%100==0 && skipped>0){ // print some progress info, though nothing has been changing for a while
                  println(s"Skipped geometry updates on $skipped linear locations")
                }
                val distanceFromHeadToHead = segment.geometry.head.distance2DTo(newGeom.head)
                val distanceFromHeadToLast = segment.geometry.head.distance2DTo(newGeom.last)
                val distanceFromLastToHead = segment.geometry.last.distance2DTo(newGeom.head)
                val distanceFromLastToLast = segment.geometry.last.distance2DTo(newGeom.last)
                if (((distanceFromHeadToHead > MinDistanceForGeometryUpdate) &&
                  (distanceFromHeadToLast > MinDistanceForGeometryUpdate)) ||
                  ((distanceFromLastToHead > MinDistanceForGeometryUpdate) &&
                    (distanceFromLastToLast > MinDistanceForGeometryUpdate))) {
                  if(skipped>0){
                    println(s"Skipped geometry updates on $skipped linear locations (minimal or no change on geometry)")
                    skipped = 0
                  }
                  updateGeometry(segment.id, newGeom)
                  println("Changed geometry on linear location id " + segment.id + " and linkId =" + segment.linkId)
                  changed += 1
                } else {
//                  println(s"Skipped geometry update on linear location ID : ${segment.id} and linkId: ${segment.linkId}")
                  skipped +=1
                }
              }
            })
          })
        }
    }
    if(skipped>0){
      println(s"Skipped geometry updates on $skipped linear locations")
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
      val ps = dynamicSession.prepareStatement(
        "UPDATE LINEAR_LOCATION SET geometry = ST_GeomFromText(?, 3067) WHERE id = ?")
      val lineString = s"LINESTRING($x1 $y1 0.0 0.0, $x2 $y2 0.0 $length)"
      ps.setString(1, lineString)
      ps.setLong(2, linearLocationId)
      ps.addBatch()
      ps.executeBatch()
    }
  }

  def updateCalibrationPointTypes(): Unit = {
    println("\nUpdating Calibration point types started at time: ")
    println(DateTime.now())
    updateCalibrationPointTypesQuery()
  }

  def updateCalibrationPointTypesQuery(): Unit = {
    SqlScriptRunner.runScriptInClasspath("/update_calibration_point_types.sql")
  }

}

case class ImportOptions(onlyComplementaryLinks: Boolean, useFrozenLinkService: Boolean, conversionTable: String, onlyCurrentRoads: Boolean)

