package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.{Combined, LeftSide, RightSide}
import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import fi.liikennevirasto.viite.dao.Discontinuity.EndOfRoad
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.{Anomaly, ProjectAddressLink, RoadAddressLink, RoadAddressLinkLike}
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
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
                            discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, withRoadAddress: Boolean = true): (RoadAddressProject, Seq[ProjectLink]) = {

    val projectId = Sequences.nextViitePrimaryKeySeqValue

    def createRoadAddresses(roadNumber: Long, roadPartNumber: Long, track: Long, start: Long, end: Long): (Long, Long) = {
      val nextLinkId = sql"""SELECT MAX(LINK_ID) FROM ROADWAY""".as[Long].first + 1
      val roadwayId = Sequences.nextRoadwayId
      val endMeasure = end - start
      sqlu"""INSERT INTO ROADWAY VALUES ($roadwayId, $roadNumber, $roadPartNumber, $track, ${discontinuity.value}, $start, $end,
            sysdate, NULL, 'test user', sysdate, 0, 0,
            MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY($start, 0, 0, 0, $end, 0, 0, $endMeasure)),
            NULL, $ely, 1, 0, NULL, 1, 0, $endMeasure, $nextLinkId, 0, sysdate, 1, NULL)""".execute
      (roadwayId, nextLinkId)
    }


    //Makes two tracks for the created links
    def withTrack(t: Track, roadNumber: Long, roadPartNumber: Long): Seq[ProjectLink] = {
      addrM.init.zip(addrM.tail).map {
        case (st, en) => {
          val (roadwayId, linkId) = if (withRoadAddress) createRoadAddresses(roadNumber, roadPartNumber, t.value, st, en) else (0L, 0L)
          projectLink(st, en, t, projectId, linkStatus, roadNumber, roadPartNumber, discontinuity, ely, linkId, roadwayId)
        }
      }
    }

    val project = RoadAddressProject(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
      "", Seq(), None, Some(ely), None)
    projectDAO.createRoadAddressProject(project)

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
    (project, projectLinkDAO.getProjectLinks(projectId))
  }

  def setUpProjectWithRampLinks(linkStatus: LinkStatus, addrM: Seq[Long]) = {
    val id = Sequences.nextViitePrimaryKeySeqValue
    val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
      "", Seq(), None, Some(8), None)
    projectDAO.createRoadAddressProject(project)
    val links = addrM.init.zip(addrM.tail).map { case (st, en) =>
      projectLink(st, en, Combined, id, linkStatus).copy(roadNumber = 39999)
    }
    projectReservedPartDAO.reserveRoadPart(id, 39999L, 1L, "u")
    projectLinkDAO.create(links.init :+ links.last.copy(discontinuity = EndOfRoad))
    project
  }

  def projectLink(startAddrM: Long, endAddrM: Long, track: Track, projectId: Long, status: LinkStatus = LinkStatus.NotHandled,
                  roadNumber: Long = 19999L, roadPartNumber: Long = 1L, discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, linkId: Long = 0L, roadwayId: Long = 0L, linearLocationId: Long = 0L) = {
    ProjectLink(NewRoadway, roadNumber, roadPartNumber, track, discontinuity, startAddrM, endAddrM, startAddrM, endAddrM, None, None,
      Some("User"), linkId, 0.0, (endAddrM - startAddrM).toDouble, SideCode.TowardsDigitizing, (None, None),
      floating = NoFloating, Seq(Point(0.0, startAddrM), Point(0.0, endAddrM)), projectId, status, RoadType.PublicRoad,
      LinkGeomSource.NormalLinkInterface, (endAddrM - startAddrM).toDouble, roadwayId, linearLocationId, ely, reversed = false, None, 0L)
  }

  def toTransition(project: RoadAddressProject, status: LinkStatus)(roadAddress: RoadAddress): (RoadAddress, ProjectLink) = {
    (roadAddress, toProjectLink(project, status)(roadAddress))
  }

  def toProjectLink(project: RoadAddressProject, status: LinkStatus)(roadAddress: RoadAddress): ProjectLink = {
    if (status == LinkStatus.New) {
      ProjectLink(roadAddress.id, roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track,
        roadAddress.discontinuity, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startDate,
        roadAddress.endDate, createdBy=Option(project.createdBy), roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue,
        roadAddress.sideCode, roadAddress.toProjectLinkCalibrationPoints(), floating=NoFloating, roadAddress.geometry, project.id, status, roadAddress.roadType,
        roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry), 0, 0, roadAddress.ely,false,
        None, roadAddress.adjustedTimestamp, roadAddressLength = Some(roadAddress.endAddrMValue - roadAddress.startAddrMValue))
    } else {
      ProjectLink(roadAddress.id, roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track,
        roadAddress.discontinuity, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startDate,
        roadAddress.endDate, createdBy=Option(project.createdBy), roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue,
        roadAddress.sideCode, roadAddress.toProjectLinkCalibrationPoints(), floating=NoFloating, roadAddress.geometry, project.id, status, roadAddress.roadType,
        roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry), roadAddress.id, roadAddress.linearLocationId, roadAddress.ely,false,
        None, roadAddress.adjustedTimestamp, roadAddressLength = Some(roadAddress.endAddrMValue - roadAddress.startAddrMValue))
    }
  }

  def toProjectLink(project: RoadAddressProject)(roadAddress: RoadAddress): ProjectLink = {
    ProjectLink(roadAddress.id, roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track,
      roadAddress.discontinuity, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startDate,
      roadAddress.endDate, createdBy=Option(project.createdBy), roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue,
      roadAddress.sideCode, roadAddress.toProjectLinkCalibrationPoints(), floating=NoFloating, roadAddress.geometry, project.id, LinkStatus.NotHandled, RoadType.PublicRoad,
      roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry), roadAddress.id, roadAddress.linearLocationId, roadAddress.ely,false,
      None, roadAddress.adjustedTimestamp, roadAddressLength = Some(roadAddress.endAddrMValue - roadAddress.startAddrMValue))
  }

  def toProjectAddressLink(ral: RoadAddressLinkLike): ProjectAddressLink = {
    ProjectAddressLink(ral.id, ral.linkId, ral.geometry, ral.length, ral.administrativeClass, ral.linkType,
      ral.constructionType, ral.roadLinkSource, ral.roadType, ral.VVHRoadName, ral.roadName, ral.municipalityCode, ral.modifiedAt, ral.modifiedBy,
      ral.attributes, ral.roadNumber, ral.roadPartNumber, ral.trackCode, ral.elyCode, ral.discontinuity,
      ral.startAddressM, ral.endAddressM, ral.startMValue, ral.endMValue, ral.sideCode, ral.startCalibrationPoint, ral.endCalibrationPoint,
      ral.anomaly, LinkStatus.Unknown, ral.id, ral.linearLocationId)
  }

  def backToProjectLink(project: RoadAddressProject)(rl: ProjectAddressLink): ProjectLink = {
    ProjectLink(rl.id, rl.roadNumber, rl.roadPartNumber, Track.apply(rl.trackCode.toInt),
      Discontinuity.apply(rl.discontinuity), rl.startAddressM, rl.endAddressM, rl.startAddressM, rl.endAddressM, None,
      None, rl.modifiedBy, rl.linkId, rl.startMValue, rl.endMValue,
      rl.sideCode, CalibrationPointsUtils.toProjectLinkCalibrationPoints((rl.startCalibrationPoint, rl.endCalibrationPoint), rl.roadwayId), floating = NoFloating, rl.geometry, project.id,
      LinkStatus.NotHandled, RoadType.PublicRoad,
      rl.roadLinkSource, GeometryUtils.geometryLength(rl.geometry), if (rl.status == LinkStatus.New) 0 else rl.id, if (rl.status == LinkStatus.New) 0 else rl.linearLocationId,  rl.elyCode, false,
      None, rl.vvhTimeStamp)
  }

  def addressToProjectLink(project: RoadAddressProject)(rl: ProjectAddressLink): ProjectLink = {
    ProjectLink(rl.id, rl.roadNumber, rl.roadPartNumber, Track.apply(rl.trackCode.toInt),
      Discontinuity.apply(rl.discontinuity), rl.startAddressM, rl.endAddressM, rl.startAddressM, rl.endAddressM, None,
      None, rl.modifiedBy, rl.linkId, rl.startMValue, rl.endMValue,
      rl.sideCode, CalibrationPointsUtils.toProjectLinkCalibrationPoints((rl.startCalibrationPoint, rl.endCalibrationPoint), rl.roadwayId), floating=NoFloating, rl.geometry, project.id,
      rl.status, RoadType.PublicRoad,
      rl.roadLinkSource, GeometryUtils.geometryLength(rl.geometry), 0, 0, rl.elyCode, reversed = false,
      None, rl.vvhTimeStamp)
  }

}
