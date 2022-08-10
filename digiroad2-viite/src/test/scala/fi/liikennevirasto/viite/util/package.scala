package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.GeometryUtils
import fi.liikennevirasto.digiroad2.asset.{AdministrativeClass, LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.{Combined, LeftSide, RightSide}
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.NoCP
import fi.liikennevirasto.viite.dao.Discontinuity.EndOfRoad
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.{Anomaly, ProjectAddressLink, RoadAddressLink, RoadAddressLinkLike}
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

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
    } link: ${l.linkId} road address: ${l.roadNumber}/${l.roadPartNumber}/${l.trackCode}/${l.startAddressM}-${l.endAddressM} length: ${l.length} dir: ${l.sideCode}
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
       |${
      if (l.anomaly != Anomaly.None) {
        " " + l.anomaly
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
    } link: ${l.linkId} ${setPrecision(l.startMValue)}-${setPrecision(l.endMValue)} road address: ${l.roadNumber}/${l.roadPartNumber}/${l.track.value}/${l.startAddrMValue}-${l.endAddrMValue} length: ${setPrecision(l.endMValue - l.startMValue)} dir: ${l.sideCode}
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
        } link: ${l.linkId} ${pl.status} ${setPrecision(l.startMValue)}-${setPrecision(l.endMValue)} road address: ${l.roadNumber}/${l.roadPartNumber}/${l.track.value}/${l.startAddrMValue}-${l.endAddrMValue} length: ${setPrecision(l.endMValue - l.startMValue)} dir: ${l.sideCode}
     """.replace("\n", "")
      case _ =>
        s"""${
          if (l.id == -1000) {
            "NEW!"
          } else {
            l.id
          }
        } link: ${l.linkId} ${setPrecision(l.startMValue)}-${setPrecision(l.endMValue)} road address: ${l.roadNumber}/${l.roadPartNumber}/${l.track.value}/${l.startAddrMValue}-${l.endAddrMValue} length: ${setPrecision(l.endMValue - l.startMValue)} dir: ${l.sideCode}
          """.replace("\n", "")
    }
  }

  private def setPrecision(d: Double) = {
    BigDecimal(d).setScale(3, BigDecimal.RoundingMode.HALF_UP).toDouble
  }

  /**
    * Method to setup a test project
    *
    * @param linkStatus  status of the links to be created
    * @param addrM
    * @param changeTrack if false creates a project with track 0, if true will create right and left side links
    * @param roads       sequence of (roadNumber, roadPartNumber, roadName) to create in project defaults to (1999L, 1L, "test road")
    * @param discontinuity
    * @param ely
    * @return
    */
  def setUpProjectWithLinks(linkStatus: LinkStatus, addrM: Seq[Long], changeTrack: Boolean = false, roads: Seq[(Long, Long, String)] = Seq((1999L, 1L, "Test road")),
                            discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, withRoadAddress: Boolean = true): (Project, Seq[ProjectLink]) = {

    val projectId = Sequences.nextViiteProjectId

    def createRoadAddresses(roadNumber: Long, roadPartNumber: Long, track: Long, start: Long, end: Long): (Long, String) = {
      val roadwayId = Sequences.nextRoadwayId
      val nextLinkId = Sequences.nextViitePrimaryKeySeqValue.toString
      val linearLocationId = Sequences.nextLinearLocationId
      val endMeasure = end - start
      sqlu"""INSERT INTO ROADWAY VALUES ($roadwayId, 1000000000, $roadNumber, $roadPartNumber, $track, $start, $end, 0, ${discontinuity.value},
            current_date, NULL, 'test user', to_timestamp('16-10-18 12.03.19.999393000','DD-MM-YY HH24.MI.SSXFF'), 0, $ely, 0, current_date, NULL)""".execute

      sqlu"""INSERT INTO LINK (ID) VALUES ($nextLinkId)""".execute

      val linestring = s"LINESTRING($start 0 0 0, $end 0 0 $endMeasure)"
      sqlu""" INSERT INTO LINEAR_LOCATION VALUES ($linearLocationId, 1000000000, 0, $nextLinkId, 0, $endMeasure, 0, ST_GeomFromText($linestring, 3067),
            current_date, null, 'test user', to_timestamp('16-10-18 12.03.19.999393000','DD-MM-YY HH24.MI.SSXFF'))""".execute
      (roadwayId, nextLinkId)
    }


    //Makes two tracks for the created links
    def withTrack(t: Track, roadNumber: Long, roadPartNumber: Long): Seq[ProjectLink] = {
      addrM.init.zip(addrM.tail).map {
        case (st, en) =>
          val (roadwayId, linkId) = if (withRoadAddress) createRoadAddresses(roadNumber, roadPartNumber, t.value, st, en) else (0L, 0L.toString)
          projectLink(st, en, t, projectId, linkStatus, roadNumber, roadPartNumber, discontinuity, ely, linkId, roadwayId)
      }
    }

    val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
      "", Seq(), Seq(), None, None)
    projectDAO.create(project)

    val links =
      roads.flatMap {
        road => {
          val (roadNumber, roadPartNumber) = (road._1, road._2)
          projectReservedPartDAO.reserveRoadPart(projectId, roadNumber, roadPartNumber, "u")
          if (changeTrack) {
            withTrack(RightSide, roadNumber, roadPartNumber) ++ withTrack(LeftSide, roadNumber, roadPartNumber)
          } else {
            withTrack(Combined, roadNumber, roadPartNumber)
          }
        }
      }
    roads.groupBy(_._1).foreach(road => ProjectLinkNameDAO.create(projectId, road._1, road._2.head._3))
    projectLinkDAO.create(links)
    (project, projectLinkDAO.fetchProjectLinks(projectId))
  }

  def setUpProjectWithRampLinks(linkStatus: LinkStatus, addrM: Seq[Long]) = {
    val id = Sequences.nextViiteProjectId
    val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
      "", Seq(), Seq(), None, None)
    projectDAO.create(project)
    val links = addrM.init.zip(addrM.tail).map { case (st, en) =>
      projectLink(st, en, Combined, id, linkStatus).copy(roadNumber = 39999)
    }
    projectReservedPartDAO.reserveRoadPart(id, 39999L, 1L, "u")
    projectLinkDAO.create(links.init :+ links.last.copy(discontinuity = EndOfRoad))
    project
  }

  def projectLink(startAddrM: Long, endAddrM: Long, track: Track, projectId: Long, status: LinkStatus = LinkStatus.NotHandled, roadNumber: Long = 19999L, roadPartNumber: Long = 1L, discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, linkId: String = "0", roadwayId: Long = 0L, linearLocationId: Long = 0L): ProjectLink = {
    ProjectLink(NewIdValue, roadNumber, roadPartNumber, track, discontinuity, startAddrM, endAddrM, startAddrM, endAddrM, None, None, Some("User"), linkId, 0.0, (endAddrM - startAddrM).toDouble, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(0.0, startAddrM), Point(0.0, endAddrM)), projectId, status, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, (endAddrM - startAddrM).toDouble, roadwayId, linearLocationId, ely, reversed = false, None, 0L)
  }

  def toTransition(project: Project, status: LinkStatus)(roadAddress: RoadAddress): (RoadAddress, ProjectLink) = {
    (roadAddress, toProjectLink(project, status)(roadAddress))
  }

  def toProjectLink(project: Project, status: LinkStatus)(roadAddress: RoadAddress): ProjectLink = {
    if (status == LinkStatus.New) {
      ProjectLink(roadAddress.id, roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track, roadAddress.discontinuity, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startDate, roadAddress.endDate, createdBy=Option(project.createdBy), roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue, roadAddress.sideCode, roadAddress.calibrationPointTypes, (NoCP, NoCP), roadAddress.geometry, project.id, status, roadAddress.administrativeClass, roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry), 0, 0, roadAddress.ely, reversed = false, None, roadAddress.adjustedTimestamp, roadAddressLength = Some(roadAddress.endAddrMValue - roadAddress.startAddrMValue))
    } else {
      ProjectLink(roadAddress.id, roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track, roadAddress.discontinuity, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startDate, roadAddress.endDate, createdBy=Option(project.createdBy), roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue, roadAddress.sideCode, roadAddress.calibrationPointTypes, (roadAddress.startCalibrationPointType, roadAddress.endCalibrationPointType), roadAddress.geometry, project.id, status, roadAddress.administrativeClass, roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry), roadAddress.id, roadAddress.linearLocationId, roadAddress.ely, reversed = false, None, roadAddress.adjustedTimestamp, roadwayNumber = roadAddress.roadwayNumber, roadAddressLength = Some(roadAddress.endAddrMValue - roadAddress.startAddrMValue))
    }
  }

  def toProjectLink(project: Project)(roadAddress: RoadAddress): ProjectLink = {
    ProjectLink(roadAddress.id, roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track, roadAddress.discontinuity, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startDate, roadAddress.endDate, createdBy=Option(project.createdBy), roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue, roadAddress.sideCode, roadAddress.calibrationPointTypes, (roadAddress.startCalibrationPointType, roadAddress.endCalibrationPointType), roadAddress.geometry, project.id, LinkStatus.NotHandled, AdministrativeClass.State, roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry), roadAddress.id, roadAddress.linearLocationId, roadAddress.ely, reversed = false, None, roadAddress.adjustedTimestamp, roadAddressLength = Some(roadAddress.endAddrMValue - roadAddress.startAddrMValue))
  }

  def toProjectAddressLink(ral: RoadAddressLinkLike): ProjectAddressLink = {
    ProjectAddressLink(ral.id, ral.linkId, ral.geometry, ral.length, ral.administrativeClassMML, ral.linkType, ral.constructionType, ral.roadLinkSource, ral.administrativeClass, ral.VVHRoadName, ral.roadName, ral.municipalityCode, ral.municipalityName, ral.modifiedAt, ral.modifiedBy, ral.attributes, ral.roadNumber, ral.roadPartNumber, ral.trackCode, ral.elyCode, ral.discontinuity, ral.startAddressM, ral.endAddressM, ral.startMValue, ral.endMValue, ral.sideCode, ral.startCalibrationPoint, ral.endCalibrationPoint, ral.anomaly, LinkStatus.Unknown, ral.id, ral.linearLocationId)
  }

  def backToProjectLink(project: Project)(rl: ProjectAddressLink): ProjectLink = {
    ProjectLink(rl.id, rl.roadNumber, rl.roadPartNumber, Track.apply(rl.trackCode.toInt), Discontinuity.apply(rl.discontinuity), rl.startAddressM, rl.endAddressM, rl.startAddressM, rl.endAddressM, None, None, rl.modifiedBy, rl.linkId, rl.startMValue, rl.endMValue, rl.sideCode, (rl.startCalibrationPointType, rl.endCalibrationPointType), (rl.startCalibrationPointType, rl.endCalibrationPointType), rl.geometry, project.id, LinkStatus.NotHandled, AdministrativeClass.State, rl.roadLinkSource, GeometryUtils.geometryLength(rl.geometry), if (rl.status == LinkStatus.New) 0 else rl.id, if (rl.status == LinkStatus.New) 0 else rl.linearLocationId, rl.elyCode, reversed = false, None, rl.vvhTimeStamp)
  }

}
