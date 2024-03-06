package fi.liikennevirasto.viite

import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.{ProjectAddressLink, RoadAddressLink, RoadAddressLinkLike}
import fi.vaylavirasto.viite.dao.{ProjectLinkNameDAO, Sequences}
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point}
import fi.vaylavirasto.viite.model.CalibrationPointType.NoCP
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, Discontinuity, LinkGeomSource, RoadAddressChangeType, RoadPart, SideCode, Track}
import fi.vaylavirasto.viite.postgis.DbUtils.runUpdateToDb
import fi.vaylavirasto.viite.postgis.PostGISDatabase
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

/**
  * Created by venholat on 14.6.2017.
  */
package object util {
  val projectDAO = new ProjectDAO
  val projectLinkDAO = new ProjectLinkDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO

  // used for debugging when needed
  def prettyPrint(l: RoadAddressLink): String = {

    s"""${
      if (l.id == -1000) {
        "NEW!"
      } else {
        l.id
      }
    } link: ${l.linkId} road address: ${l.roadPart}/${l.trackCode}/${l.addrMRange} length: ${l.length} dir: ${l.sideCode}
       |${
      if (l.startCalibrationPoint.nonEmpty) {
        " <- " + l.startCalibrationPoint.get.addressMValue + " "
      } else ""
    }
       |${
      if (l.endCalibrationPoint.nonEmpty) {
        " " + l.endCalibrationPoint.get.addressMValue + " ->"
      } else ""
    }
     """.stripMargin.replace("\n", "")
  }

  // used for debugging when needed
  def prettyPrint(l: RoadAddress): String = {

    s"""${
      if (l.id == -1000) {
        "NEW!"
      } else {
        l.id
      }
    } link: ${l.linkId} ${setPrecision(l.startMValue)}-${setPrecision(l.endMValue)} road address: ${l.roadPart}/${l.track.value}/${l.addrMRange} length: ${setPrecision(l.endMValue - l.startMValue)} dir: ${l.sideCode}
       |${
      if (l.startCalibrationPoint.nonEmpty) {
        " <- " + l.startCalibrationPoint.get.addressMValue + " "
      } else ""
    }
       |${
      if (l.endCalibrationPoint.nonEmpty) {
        " " + l.endCalibrationPoint.get.addressMValue + " ->"
      } else ""
    }
       |${l.startDate.map(_.toString(" d.MM.YYYY -")).getOrElse("")} ${l.endDate.map(_.toString("d.MM.YYYY")).getOrElse("")}
       |${if (l.terminated.value != 0) " ✞" else ""}
     """.stripMargin.replace("\n", "")
  }

  // used for debugging when needed
  def prettyPrint(l: BaseRoadAddress): String = {
    l match {
      case pl: ProjectLink =>
        s"""${
          if (l.id == -1000) {
            "NEW!"
          } else {
            l.id
          }
        } link: ${l.linkId} ${pl.status} ${setPrecision(l.startMValue)}-${setPrecision(l.endMValue)} road address: ${l.roadPart}/${l.track.value}/${l.addrMRange} length: ${setPrecision(l.endMValue - l.startMValue)} dir: ${l.sideCode}
     """.replace("\n", "")
      case _ =>
        s"""${
          if (l.id == -1000) {
            "NEW!"
          } else {
            l.id
          }
        } link: ${l.linkId} ${setPrecision(l.startMValue)}-${setPrecision(l.endMValue)} road address: ${l.roadPart}/${l.track.value}/${l.addrMRange} length: ${setPrecision(l.endMValue - l.startMValue)} dir: ${l.sideCode}
          """.replace("\n", "")
    }
  }

  private def setPrecision(d: Double) = {
    BigDecimal(d).setScale(3, BigDecimal.RoundingMode.HALF_UP).toDouble
  }

  /**
    * Method to setup a test project
    *
    * @param roadAddressChangeType  status/changeType of the links to be created
    * @param addrM
    * @param changeTrack if false creates a project with track 0, if true will create right and left side links
    * @param roads       sequence of (roadPart, roadName) to create in project defaults to (RoadPart(1999, 1), "test road")
    * @param discontinuity
    * @param ely
    * @return
    */
  def setUpProjectWithLinks(roadAddressChangeType: RoadAddressChangeType, addrM: Seq[Long], changeTrack: Boolean = false, roads: Seq[(RoadPart, String)] = Seq((RoadPart(1999, 1), "Test road")),
                            discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, withRoadAddress: Boolean = true): (Project, Seq[ProjectLink]) = {

    val projectId = Sequences.nextViiteProjectId

    def createRoadAddresses(roadPart: RoadPart, track: Long, start: Long, end: Long): (Long, String) = {
      val roadwayId = Sequences.nextRoadwayId
      val nextLinkId = Sequences.nextViitePrimaryKeySeqValue.toString
      val linearLocationId = Sequences.nextLinearLocationId
      val endMeasure = end - start
      runUpdateToDb(s"""INSERT INTO ROADWAY VALUES (
                  $roadwayId, 1000000000, ${roadPart.roadNumber}, ${roadPart.partNumber}, $track, $start, $end, 0, ${discontinuity.value}, current_date, NULL,
                  'test user', to_timestamp('16-10-18 12.03.19.999393000','DD-MM-YY HH24.MI.SSXFF'), 0, $ely, 0, current_date, NULL)""")

      runUpdateToDb(s"INSERT INTO LINK (ID) VALUES ($nextLinkId)")

      val linestring = s"LINESTRING($start 0 0 0, $end 0 0 $endMeasure)"
      runUpdateToDb(s"""INSERT INTO LINEAR_LOCATION VALUES (
                  $linearLocationId, 1000000000, 0, $nextLinkId, 0, $endMeasure, 0, ST_GeomFromText('$linestring', 3067),
                  current_date, null, 'test user', to_timestamp('16-10-18 12.03.19.999393000','DD-MM-YY HH24.MI.SSXFF'))""".stripMargin)
      (roadwayId, nextLinkId)
    }


    //Makes two tracks for the created links
    def withTrack(t: Track, roadPart: RoadPart): Seq[ProjectLink] = {
      addrM.init.zip(addrM.tail).map {
        case (st, en) =>
          val (roadwayId, linkId) = if (withRoadAddress) createRoadAddresses(roadPart, t.value, st, en) else (0L, 0L.toString)
          projectLink(AddrMRange(st, en), t, projectId, roadAddressChangeType, roadPart, discontinuity, ely, linkId, roadwayId)
      }
    }

    val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
      "", Seq(), Seq(), None, None)
    projectDAO.create(project)

    val links =
      roads.flatMap {
        road => {
          projectReservedPartDAO.reserveRoadPart(projectId, road._1, "u")
          if (changeTrack) {
            withTrack(Track.RightSide, road._1) ++ withTrack(Track.LeftSide, road._1)
          } else {
            withTrack(Track.Combined, road._1)
          }
        }
      }
    val roadsByNumber = roads.groupBy(_._1.roadNumber)
    roadsByNumber.foreach(road => {val samplePart = road._2.head; ProjectLinkNameDAO.create(projectId, samplePart._1.roadNumber, samplePart._2)})
    projectLinkDAO.create(links)
    (project, projectLinkDAO.fetchProjectLinks(projectId))
  }

  def setUpProjectWithRampLinks(roadAddressChangeType: RoadAddressChangeType, addrM: Seq[Long]) = {
    val id = Sequences.nextViiteProjectId
    val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
      "", Seq(), Seq(), None, None)
    projectDAO.create(project)
    val links = addrM.init.zip(addrM.tail).map { case (st, en) =>
      val pl = projectLink(AddrMRange(st, en), Track.Combined, id, roadAddressChangeType)
      pl.copy(roadPart = RoadPart(39999, pl.roadPart.partNumber))
    }
    projectReservedPartDAO.reserveRoadPart(id, RoadPart(39999, 1), "u")
    projectLinkDAO.create(links.init :+ links.last.copy(discontinuity = Discontinuity.EndOfRoad))
    project
  }

  def projectLink(addrMRange: AddrMRange, track: Track, projectId: Long, status: RoadAddressChangeType = RoadAddressChangeType.NotHandled, roadPart: RoadPart = RoadPart(19999, 1), discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, linkId: String = "0", roadwayId: Long = 0L, linearLocationId: Long = 0L): ProjectLink = {
    ProjectLink(NewIdValue, roadPart, track, discontinuity, addrMRange, addrMRange, None, None, Some("User"), linkId, 0.0, (addrMRange.endAddrM-addrMRange.startAddrM).toDouble, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(0.0, addrMRange.startAddrM), Point(0.0, addrMRange.endAddrM)), projectId, status, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, (addrMRange.endAddrM-addrMRange.startAddrM).toDouble, roadwayId, linearLocationId, ely, reversed = false, None, 0L)
  }

  def toTransition(project: Project, status: RoadAddressChangeType)(roadAddress: RoadAddress): (RoadAddress, ProjectLink) = {
    (roadAddress, toProjectLink(project, status)(roadAddress))
  }

  def toProjectLink(project: Project, status: RoadAddressChangeType)(roadAddress: RoadAddress): ProjectLink = {
    if (status == RoadAddressChangeType.New) {
      ProjectLink(roadAddress.id, roadAddress.roadPart, roadAddress.track, roadAddress.discontinuity, roadAddress.addrMRange, roadAddress.addrMRange, roadAddress.startDate, roadAddress.endDate, createdBy=Option(project.createdBy), roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue, roadAddress.sideCode, roadAddress.calibrationPointTypes, (NoCP, NoCP), roadAddress.geometry, project.id, status, roadAddress.administrativeClass, roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry), 0, 0, roadAddress.ely, reversed = false, None, roadAddress.adjustedTimestamp, roadAddressLength = Some(roadAddress.addrMRange.endAddrM-roadAddress.addrMRange.startAddrM))
    } else {
      ProjectLink(roadAddress.id, roadAddress.roadPart, roadAddress.track, roadAddress.discontinuity, roadAddress.addrMRange, roadAddress.addrMRange, roadAddress.startDate, roadAddress.endDate, createdBy=Option(project.createdBy), roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue, roadAddress.sideCode, roadAddress.calibrationPointTypes, (roadAddress.startCalibrationPointType, roadAddress.endCalibrationPointType), roadAddress.geometry, project.id, status, roadAddress.administrativeClass, roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry), roadAddress.id, roadAddress.linearLocationId, roadAddress.ely, reversed = false, None, roadAddress.adjustedTimestamp, roadwayNumber = roadAddress.roadwayNumber, roadAddressLength = Some(roadAddress.addrMRange.endAddrM-roadAddress.addrMRange.startAddrM))
    }
  }

  def toProjectLink(project: Project)(roadAddress: RoadAddress): ProjectLink = {
    ProjectLink(roadAddress.id, roadAddress.roadPart, roadAddress.track, roadAddress.discontinuity, roadAddress.addrMRange, roadAddress.addrMRange, roadAddress.startDate, roadAddress.endDate, createdBy=Option(project.createdBy), roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue, roadAddress.sideCode, roadAddress.calibrationPointTypes, (roadAddress.startCalibrationPointType, roadAddress.endCalibrationPointType), roadAddress.geometry, project.id, RoadAddressChangeType.NotHandled, AdministrativeClass.State, roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry), roadAddress.id, roadAddress.linearLocationId, roadAddress.ely, reversed = false, None, roadAddress.adjustedTimestamp, roadAddressLength = Some(roadAddress.addrMRange.endAddrM-roadAddress.addrMRange.startAddrM))
  }

  def toProjectAddressLink(ral: RoadAddressLinkLike): ProjectAddressLink = {
    ProjectAddressLink(ral.id, ral.linkId, ral.geometry, ral.length, ral.administrativeClassMML, ral.lifecycleStatus, ral.roadLinkSource, ral.administrativeClass, ral.roadName, ral.municipalityCode, ral.municipalityName, ral.modifiedAt, ral.modifiedBy, ral.roadPart, ral.trackCode, ral.elyCode, ral.discontinuity, ral.addrMRange, ral.startMValue, ral.endMValue, ral.sideCode, ral.startCalibrationPoint, ral.endCalibrationPoint, RoadAddressChangeType.Unknown, ral.id, ral.linearLocationId, sourceId = ral.sourceId)
  }

  def backToProjectLink(project: Project)(rl: ProjectAddressLink): ProjectLink = {
    ProjectLink(rl.id, rl.roadPart, Track.apply(rl.trackCode.toInt), Discontinuity.apply(rl.discontinuity), rl.addrMRange, rl.addrMRange, None, None, rl.modifiedBy, rl.linkId, rl.startMValue, rl.endMValue, rl.sideCode, (rl.startCalibrationPointType, rl.endCalibrationPointType), (rl.startCalibrationPointType, rl.endCalibrationPointType), rl.geometry, project.id, RoadAddressChangeType.NotHandled, AdministrativeClass.State, rl.roadLinkSource, GeometryUtils.geometryLength(rl.geometry), if (rl.status == RoadAddressChangeType.New) 0 else rl.id, if (rl.status == RoadAddressChangeType.New) 0 else rl.linearLocationId, rl.elyCode, reversed = false, None, rl.roadLinkTimeStamp)
  }

}
