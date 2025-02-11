package fi.liikennevirasto.viite.util

import fi.liikennevirasto.digiroad2.{DummyEventBus, DummySerializer}
import fi.liikennevirasto.digiroad2.client.kgv.KgvRoadLink
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.{SqlScriptRunner, ViiteProperties}
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao._
import fi.vaylavirasto.viite.dao.{BaseDAO, SequenceResetterDAO}
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point}
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC
import fi.vaylavirasto.viite.postgis.SessionProvider.withSession
import org.joda.time.DateTime
import scalikejdbc._


object DataImporter {

  val ConversionPoolName = Symbol("conversionDb")

  object Conversion {
    def setupConversionPool(): Unit = {
      ConnectionPool.add(
        name = ConversionPoolName,
        url = ViiteProperties.conversionBonecpJdbcUrl,
        user = ViiteProperties.conversionBonecpUsername,
        password = ViiteProperties.conversionBonecpPassword
      )
    }

    def database(): NamedDB = {
      if (!ConnectionPool.isInitialized(ConversionPoolName)) {
        setupConversionPool()
      }
      NamedDB(ConversionPoolName)
    }

    def runWithConversionDbTransaction(f: => Unit): Unit = {
      database().localTx { session =>
        withSession(session) {
          f
        }
      }
    }

    def runWithConversionDbReadOnlySession[T](f: => T): T = {
      database().readOnly { session =>
        withSession(session) {
          f
        }
      }
    }
  }
}

class DataImporter extends BaseDAO {


  private lazy val geometryFrozen: Boolean = ViiteProperties.kgvRoadlinkFrozen

  // For database operations with the main db:
  private def runWithMainDbTransaction(f: => Unit): Unit = PostGISDatabaseScalikeJDBC.runWithTransaction(f)

  private def runWithMainDBReadOnlySession[T](f: => T): T = PostGISDatabaseScalikeJDBC.runWithReadOnlySession(f)

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
        importRoadAddressData(KGVClient, importOptions)
        println(s"Road address import complete at time: ${DateTime.now()}")
    }
  }

  def importRoadAddressData(KGVClient: KgvRoadLink, importOptions: ImportOptions): Unit = {

    println(s"\nimportRoadAddressData    started at time:  ${DateTime.now()}")
    runWithMainDbTransaction {

      println(s"\nDisabling roadway triggers started at time: ${DateTime.now()}")
      disableRoadwayTriggers
      println(s"\nDeleting old Alkulataus tables' data")
      println(s"  Deleting project_link_names         started at time: ${DateTime.now()}")
      runUpdateToDb(sql"""DELETE FROM project_link_name""")
      println(s"  Deleting roadway_changes              started at time: ${DateTime.now()}")
      runUpdateToDb(sql"""DELETE FROM roadway_changes_LINK""")
      println(s"  Deleting project_links                  started at time: ${DateTime.now()}")
      runUpdateToDb(sql"""DELETE FROM project_link""")
      println(s"  Deleting PROJECT_INK_LHISTORY             started at time: ${DateTime.now()}")
      runUpdateToDb(sql"""DELETE FROM project_link_history""")
      println(s"  Deleting project_reserved_road_parts links  started at time: ${DateTime.now()}")
      runUpdateToDb(sql"""DELETE FROM project_reserved_road_part""")


      // Delete other than accepted projects.
      // Accepted states: 0 = ProjectDAO.ProjectState.Accepted; 5 = ProjectState.DeprecatedSaved2ToTR
      println(s"  Deleting PROJECTs (state != 12|5)           started at time: ${DateTime.now()}")
      runUpdateToDb(sql"""DELETE FROM project WHERE STATE != 12 AND STATE != 5""")

      println(s"  Deleting roadway_changess                 started at time: ${DateTime.now()}")
      runUpdateToDb(
        sql"""
               DELETE FROM roadway_changes
               WHERE project_id NOT IN (SELECT id FROM project)
               """
      )

      println(s"  Deleting linear_locations         started at time: ${DateTime.now()}")
      runUpdateToDb(sql"""DELETE FROM linear_location""")
      println(s"  Deleting calibration_points     started at time: ${DateTime.now()}")
      runUpdateToDb(sql"""DELETE FROM calibration_point""")
      println(s"  Deleting junction_points      started at time: ${DateTime.now()}")
      runUpdateToDb(sql"""DELETE FROM junction_point""")
      println(s"  Deleting node_points        started at time: ${DateTime.now()}")
      runUpdateToDb(sql"""DELETE FROM node_point""")
      println(s"  Deleting roadway_points   started at time: ${DateTime.now()}")
      runUpdateToDb(sql"""DELETE FROM roadway_point""")
      println(s"  Deleting junctions      started at time: ${DateTime.now()}")
      runUpdateToDb(sql"""DELETE FROM junction""")
      println(s"  Deleting nodes        started at time: ${DateTime.now()}")
      runUpdateToDb(sql"""DELETE FROM node""")
      println(s"  Deleting LINKs      started at time: ${DateTime.now()}")
      runUpdateToDb(sql"""DELETE FROM link""")
      println(s"  Deleting roadways started at time: ${DateTime.now()}")
      runUpdateToDb(sql"""DELETE FROM roadway""")

      resetRoadAddressSequences()


      println(s"${DateTime.now()} - Old address data removed")

      val roadAddressImporter = getRoadAddressImporter(KGVClient, importOptions)
      roadAddressImporter.importRoadAddress()


      println(s"\n${DateTime.now()} - Updating terminated roadways information")
      runUpdateToDb(
        sql"""
              UPDATE roadway SET terminated = 2
              WHERE terminated = 0 AND end_date IS NOT NULL AND EXISTS (SELECT 1 FROM roadway rw
                WHERE roadway.road_number = rw.road_number
                AND roadway.roadway_number = rw.roadway_number
                AND roadway.road_part_number = rw.road_part_number
                AND roadway.start_addr_m = rw.start_addr_m
                AND roadway.end_addr_m = rw.end_addr_m
                AND roadway.track = rw.track
                AND roadway.end_date = rw.start_date - 1
                AND rw.valid_to IS NULL AND rw.terminated = 1)
                """
      )

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
    runWithMainDbTransaction {
      println("\nImporting nodes and junctions started at time: ")
      println(DateTime.now())

      runUpdateToDb(sql"""DELETE FROM junction_point""")
      runUpdateToDb(sql"""DELETE FROM node_point""")
      runUpdateToDb(sql"""DELETE FROM junction""")
      runUpdateToDb(sql"""DELETE FROM node""")
      resetNodesAndJunctionSequences()

      println(s"${DateTime.now()} - Old nodes and junctions data removed")
      val nodeImporter = getNodeImporter
      nodeImporter.importNodes()
      val junctionImporter = getJunctionImporter
      junctionImporter.importJunctions()
      updateNodePointType()
    }
  }

  def resetRoadAddressSequences(): Unit = {
    println("\nResetting road related sequences started at time: ")
    println(DateTime.now())

    val sequenceResetter = new SequenceResetterDAO()
    sequenceResetter.resetSequenceToNumber("project_link_name_seq", 1)
    sequenceResetter.resetSequenceToNumber("roadway_CHANGE_LINK", 1)
    sequenceResetter.resetSequenceToNumber("road_network_error_seq", 1)

    //@deprecated ("Table published_road_network is no longer in use, and is empty.")
    sequenceResetter.resetSequenceToNumber("published_road_network_seq", 1)

    sequenceResetter.resetSequenceToNumber("linear_location_seq", 1)
    sequenceResetter.resetSequenceToNumber("calibration_point_seq", 1)
    sequenceResetter.resetSequenceToNumber("roadway_point_seq", 1)
    sequenceResetter.resetSequenceToNumber("roadway_seq", 1)
    sequenceResetter.resetSequenceToNumber("VIITE_GENERAL_seq", 1)
  }

  def resetNodesAndJunctionSequences(): Unit = {
    println("\nResetting nodes & junctions related sequences started at time: ")
    println(DateTime.now())

    val sequenceResetter = new SequenceResetterDAO()
    sequenceResetter.resetSequenceToNumber("junction_point_seq", 1)
    sequenceResetter.resetSequenceToNumber("node_point_seq", 1)
    sequenceResetter.resetSequenceToNumber("junction_seq", 1)
    sequenceResetter.resetSequenceToNumber("node_seq", 1)
  }

  private def updateNodePointType(): Unit = {
    println("\nUpdating nodePointTypes started at time: ")
    println(DateTime.now())

    runUpdateToDb(
      sql"""
          UPDATE node_point np SET TYPE = (SELECT CASE
              -- [TYPE = 99] Includes expired node points points or points attached to expired nodes
              WHEN (point.valid_to IS NOT NULL OR NOT EXISTS (SELECT 1 FROM node node
                WHERE node.node_number = point.node_number AND (node.end_date IS NULL AND node.valid_to IS NULL))) THEN 99
              -- [TYPE = 1] Includes templates, points where addr_m is equal to start_addr_m or end_addr_m of the road (road_number, road_part_number and track) and when administrative_class changes
              WHEN point.node_number IS NULL THEN 1 -- node point template
              WHEN (rp.addr_m = (SELECT MIN(roadAddr.start_addr_m) FROM roadway roadAddr
                WHERE roadAddr.road_number = rw.road_number AND roadAddr.road_part_number = rw.road_part_number
                AND roadAddr.valid_to IS NULL AND roadAddr.end_date IS NULL)) THEN 1 -- addr_m is equal to start_addr_m
              WHEN (rp.addr_m = (SELECT MAX(roadAddr.end_addr_m) FROM roadway roadAddr
                WHERE roadAddr.road_number = rw.road_number AND roadAddr.road_part_number = rw.road_part_number
                AND roadAddr.valid_to IS NULL AND roadAddr.end_date IS NULL)) THEN 1 -- addr_m is equal to end_addr_m
              WHEN ((SELECT DISTINCT(roadAddr.administrative_class) FROM roadway roadAddr
                  WHERE roadAddr.road_number = rw.road_number AND roadAddr.road_part_number = rw.road_part_number AND roadAddr.start_addr_m = rp.addr_m
                  AND roadAddr.valid_to IS NULL AND roadAddr.end_date IS NULL) !=
                (SELECT DISTINCT(roadAddr.administrative_class) FROM roadway roadAddr
                  WHERE roadAddr.road_number = rw.road_number AND roadAddr.road_part_number = rw.road_part_number AND roadAddr.end_addr_m = rp.addr_m
                  AND roadAddr.valid_to IS NULL AND roadAddr.end_date IS NULL)) THEN 1 -- administrative_class changed on addr_m
              -- [TYPE = 2]
              ELSE 2
            END AS node_point_type
            FROM node_point point
            LEFT JOIN roadway_point rp ON point.roadway_point_ID = rp.id
            LEFT JOIN roadway rw ON rp.roadway_number = rw.roadway_number AND rw.valid_to IS NULL AND rw.end_date IS NULL
              WHERE point.id = np.id
              LIMIT 1)
              """
    )
  }

  def updateLinearLocationGeometry(): Unit = {
    println(s"\nUpdating road address table geometries at time: ${DateTime.now()}")
    val KGVClient = new KgvRoadLink
    updateLinearLocationGeometry(KGVClient, geometryFrozen)
    println(s"Road addresses geometry update complete at time: ${DateTime.now()}")
    println()
  }

  def enableRoadwayTriggers(): Unit = {
    runUpdateToDb(s"""ALTER TABLE ROADWAY ENABLE TRIGGER USER""")
  }

  def disableRoadwayTriggers(): Unit = {
    runUpdateToDb(sql"""ALTER TABLE roadway DISABLE TRIGGER USER""")
  }

  /** Resets the roadway sequence to (MAX-of-current-roadway-numbers)+1, or to 1, if no roadways available. */
  def roadwaySequenceResetter(): Unit = {
    println(s"\nResetting roadway related sequences started at time: ${DateTime.now()}")

    val sequenceResetter = new SequenceResetterDAO()
    runSelectSingleFirstOptionWithType[Long](sql"""select MAX(roadway_number) FROM roadway""") match {
      case Some(roadwayNumber) =>
        sequenceResetter.resetSequenceToNumber("roadway_number_seq", roadwayNumber + 1)
      case _ => sequenceResetter.resetSequenceToNumber("roadway_number_seq", 1)
    }
  }

  protected def getRoadAddressImporter(KGVClient: KgvRoadLink, importOptions: ImportOptions): RoadAddressImporter = {
    new RoadAddressImporter(KGVClient, importOptions)
  }

  protected def getNodeImporter: NodeImporter = {
    new NodeImporter
  }

  protected def getJunctionImporter: JunctionImporter = {
    new JunctionImporter
  }

  protected def fetchGroupedLinkIds: Seq[Set[String]] = {
    val query =
      sql"""
               SELECT DISTINCT link_id
               FROM linear_location
               WHERE link_id IS NOT NULL
               ORDER BY link_id
               """

    val linkIds = runSelectQuery(query.map(_.string(1)))
    linkIds.toSet.grouped(25000).toSeq
  }

  private def updateLinearLocationGeometry(KGVClient: KgvRoadLink, geometryFrozen: Boolean): Unit = {
    val eventBus = new DummyEventBus
    val linearLocationDAO = new LinearLocationDAO
    val linkService = new RoadLinkService(KGVClient, eventBus, new DummySerializer, geometryFrozen)
    var changed = 0
    var skipped = 0 /// For log information about update-skipped linear locations, skip due to sameness to the old data
    val linkIds = runWithMainDBReadOnlySession {
      fetchGroupedLinkIds
    }
    linkIds.par.foreach {
      case linkIds =>
        runWithMainDbTransaction {
          val roadLinksFromKGV = linkService.getCurrentAndComplementaryRoadLinks(linkIds)
          val unGroupedTopology = linearLocationDAO.fetchByLinkId(roadLinksFromKGV.map(_.linkId).toSet)
          val topologyLocation = unGroupedTopology.groupBy(_.linkId)
          roadLinksFromKGV.foreach(roadLink => {
            val segmentsOnViiteDatabase = topologyLocation.getOrElse(roadLink.linkId, Set())
            segmentsOnViiteDatabase.foreach(segment => {
              val newGeom = GeometryUtils.truncateGeometry3D(roadLink.geometry, segment.startMValue, segment.endMValue)
              if (!segment.geometry.equals(Nil) && !newGeom.equals(Nil)) {
                if (skipped % 100 == 0 && skipped > 0) { // print some progress info, though nothing has been changing for a while
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
                  if (skipped > 0) {
                    println(s"Skipped geometry updates on $skipped linear locations (minimal or no change on geometry)")
                    skipped = 0
                  }
                  updateGeometry(segment.id, newGeom)
                  println("Changed geometry on linear location id " + segment.id + " and linkId =" + segment.linkId)
                  changed += 1
                } else {
                  //                  println(s"Skipped geometry update on linear location ID : ${segment.id} and linkId: ${segment.linkId}")
                  skipped += 1
                }
              }
            })
          })
        }
    }
    if (skipped > 0) {
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
      val lineString = s"LINESTRING($x1 $y1 0.0 0.0, $x2 $y2 0.0 $length)"

      val updateQuery = sql"""
        UPDATE linear_location
        SET geometry = ST_GeomFromText($lineString, 3067)
        WHERE id = $linearLocationId
      """

      runUpdateToDb(updateQuery)
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

