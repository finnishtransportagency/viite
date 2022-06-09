package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import fi.liikennevirasto.digiroad2.asset.{AdministrativeClass, LinkGeomSource}
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.{FrozenLinkInterface, NormalLinkInterface}
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.{LeftSide, RightSide}
import fi.liikennevirasto.viite.NewIdValue
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.{JunctionPointCP, NoCP, RoadAddressCP}
import fi.liikennevirasto.viite.dao.Discontinuity.{Continuous, EndOfRoad, MinorDiscontinuity}
import fi.liikennevirasto.viite.dao.LinkStatus.{Terminated, Transfer}
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.process.ProjectDeltaCalculator.createTwoTrackOldAddressRoadParts
import fi.liikennevirasto.viite.util.{toProjectLink, toTransition}
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}
import org.scalatest.enablers.Definition.definitionOfOption
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

import scala.collection.immutable

class ProjectDeltaCalculatorSpec extends FunSuite with Matchers {
  val roadwayDAO = new RoadwayDAO

  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)

  def runWithRollback[T](f: => T): T = {
    Database.forDataSource(PostGISDatabase.ds).withDynTransaction {
      val t = f
      dynamicSession.rollback()
      t
    }
  }

  private def createRoadAddress(start: Long, distance: Long, roadwayNumber: Long = 0L) = {
    //TODO the road address now have the linear location id and has been set to 1L
    RoadAddress(id = start, linearLocationId = 1L, roadNumber = 5, roadPartNumber = 205, administrativeClass = AdministrativeClass.State, track = Track.Combined, discontinuity = Continuous, startAddrMValue = start, endAddrMValue = start + distance, linkId = start, startMValue = 0.0, endMValue = distance.toDouble, sideCode = TowardsDigitizing, adjustedTimestamp = 0L, geometry = Seq(Point(0.0, start), Point(0.0, start + distance)), linkGeomSource = NormalLinkInterface, ely = 8, terminated = NoTermination, roadwayNumber = roadwayNumber)
  }

  private val project: Project = Project(13L, ProjectState.Incomplete, "foo", "user", DateTime.now(), "user", DateTime.now(),
    DateTime.now(), "", Seq(), Seq(), None, None)

  private def createTransferProjectLink(start: Long, distance: Long) = {
    toProjectLinkWithMove(project, LinkStatus.Transfer)(createRoadAddress(start, distance))
  }

  private def toProjectLinkWithMove(project: Project, status: LinkStatus)(roadAddress: RoadAddress): ProjectLink = {
    ProjectLink(roadAddress.id, roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track,
      roadAddress.discontinuity, roadAddress.startAddrMValue + project.id, roadAddress.endAddrMValue + project.id, roadAddress.startAddrMValue + project.id, roadAddress.endAddrMValue + project.id, roadAddress.startDate,
      roadAddress.endDate, createdBy = Option(project.createdBy), roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue,
      roadAddress.sideCode, roadAddress.calibrationPointTypes, (roadAddress.startCalibrationPointType, roadAddress.endCalibrationPointType), roadAddress.geometry, project.id, status,
      roadAddress.administrativeClass, roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry), roadAddress.id, roadAddress.linearLocationId, roadAddress.ely, reversed = false,
      None, 748800L)
  }

  def toRoadway(ps: Seq[ProjectLink]): Roadway = {
    val p = ps.head
    val startDate = p.startDate.getOrElse(DateTime.now()).minusDays(1)
    Roadway(p.roadwayId, p.roadwayNumber, p.roadNumber, p.roadPartNumber, p.administrativeClass, p.track, p.discontinuity, ps.head.startAddrMValue, ps.last.endAddrMValue, p.reversed, startDate, p.endDate, p.createdBy.getOrElse("-"), p.roadName, p.ely, TerminationCode.NoTermination, DateTime.now(), None)
  }

  test("Test ProjectDeltaCalculator.partition When executing multiple transfers on single road part Then returns the correct From RoadSection -> To RoadSection mapping.") {
    val transfer1 = (0 to 10).map(x => (createRoadAddress(x * 10, 10), createTransferProjectLink(x * 10, 10)))
    val transfer2 = (12 to 15).map(x => (createRoadAddress(x * 10, 10), createTransferProjectLink(x * 10, 10)))
    val mapping =
      ProjectDeltaCalculator.partition(transfer1 ++ transfer2).adjustedSections.map(_._1)
    mapping.foreach { elem =>
      elem._1.startMAddr should be(elem._2.startMAddr - project.id)
      elem._1.endMAddr should be(elem._2.endMAddr - project.id)
      elem._1.track should be(elem._2.track)
    }
  }

  test("Test ProjectDeltaCalculator.partition When executing a Unchanged and 2 transfer on single road part Then returns the correct From RoadSection -> To RoadSection mapping.") {
    runWithRollback {
      val addresses  = (0 to 10).map(i => {
        createRoadAddress(i * 10, 10L)
      })
      val addresses2 = (11 to 21).map(i => {
        createRoadAddress(i * 10, 10L)
      }).map(a => {
        a.copy(roadPartNumber = 206, startAddrMValue = a.startAddrMValue - 110L, endAddrMValue = a.endAddrMValue - 110L)
      })
      val (transferLinks1, transferLinks2) = addresses2.map(toTransition(project, LinkStatus.Transfer)).partition(_._2.startAddrMValue == 0L)
      val projectLinks                     = addresses.map(toTransition(project, LinkStatus.UnChanged)) ++ transferLinks1.map(l => {
        (l._1, l._2.copy(roadPartNumber = 205, startAddrMValue = 110L, endAddrMValue = 120L))
      }) ++ transferLinks2.map(l => {
        (l._1, l._2.copy(startAddrMValue = l._2.startAddrMValue - 10L, endAddrMValue = l._2.endAddrMValue - 10L))
      })
      val roadway205 = toRoadway(addresses.map(toTransition(project, LinkStatus.UnChanged)).map(_._2))
      val roadway206 = toRoadway(addresses2.map(toTransition(project, LinkStatus.Transfer)).map(_._2))
      roadwayDAO.create(Seq(roadway205, roadway206))

      val partitions = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(projectLinks.map(_._2), projectLinks.map(_._2))
      val partitions2 = partitions.adjustedSections.zip(partitions.originalSections)

      partitions2 should have size 3
      val start205  = partitions2.find(p => {
        p._1.roadPartNumberStart == 205 && p._2.roadPartNumberStart == 205
      })
      val to205     = partitions2.find(p => {
        p._1.roadPartNumberStart == 205 && p._2.roadPartNumberStart == 206
      })
      val remain205 = partitions2.find(p => {
        p._1.roadPartNumberStart == 206 && p._2.roadPartNumberStart == 206
      })

      start205.size should be(1)
      to205.size should be(1)
      remain205.size should be(1)
      start205.map(x => (x._1.startMAddr, x._2.startMAddr, x._1.endMAddr, x._2.endMAddr)) should be(Some((0L, 0L, 110L, 110L)))
    }
  }

  test("Test ProjectDeltaCalculator.partition " +
       "When a road with a combined part + two track part is reversed" +
       "Then returns the correct From RoadSection -> To RoadSection mapping.") {
    implicit val ordering: Ordering[RoadAddress] = Ordering.by(_.endAddrMValue)
    def getMinAddress(pls: Seq[BaseRoadAddress]): Long = pls.minBy(_.startAddrMValue).startAddrMValue
    def getMaxAddress(pls: Seq[BaseRoadAddress]): Long = pls.maxBy(_.endAddrMValue).endAddrMValue
    def addressTrackChanges(x: (RoadwaySection, RoadwaySection)): (Long, Long, Long, Long, Track, Track) = (x._1.startMAddr, x._2.startMAddr, x._1.endMAddr, x._2.endMAddr, x._1.track, x._2.track)
    def toProjectLinks(transferLinks: IndexedSeq[(RoadAddress, ProjectLink)], track: Track)(implicit addresses: Seq[RoadAddress]): IndexedSeq[ProjectLink] = {
      val roadwayId = transferLinks.head._2.roadwayId
      transferLinks.map(l => {
        l._2.copy(reversed = true, track = track, startAddrMValue = addresses.max.endAddrMValue - l._2.endAddrMValue, endAddrMValue = addresses.max.endAddrMValue - l._2.startAddrMValue, roadwayId = roadwayId)
      })
    }
    runWithRollback {
      val distance = 10L
      val combinedTrackAddresses  = (0 to 10).map(f = i => {
        createRoadAddress(i * 10, distance)
      })
      val leftTrackAddresses = (11 to 21).map(i => {
        createRoadAddress(i * 10, distance)
      }).map(f = a => {a.copy(track = Track.LeftSide)})
      implicit val rightTrackAddresses: immutable.IndexedSeq[RoadAddress] = (11 to 21).map(i => {
        createRoadAddress(i * 10, distance)
      }).map(f = a => a.copy(id = a.id + 1, track = Track.RightSide))

      val transferLinks0 = combinedTrackAddresses.map(toTransition(project, LinkStatus.Transfer))
      val transferLinks1 = rightTrackAddresses.map(toTransition(project, LinkStatus.Transfer))
      val transferLinks2 = leftTrackAddresses.map(toTransition(project, LinkStatus.Transfer))

      val projectLinks = toProjectLinks(transferLinks0, Track.Combined) ++ toProjectLinks(transferLinks1, Track.LeftSide) ++ toProjectLinks(transferLinks2, Track.RightSide)

      val combinedLinks = projectLinks.filter(_.track == Track.Combined)
      val rightLinks    = projectLinks.filter(_.track == Track.RightSide)
      val leftLinks     = projectLinks.filter(_.track == Track.LeftSide)

      val roadway0      = toRoadway(combinedLinks).copy(track = combinedTrackAddresses.head.track, startAddrMValue = 0, endAddrMValue = combinedTrackAddresses.max.endAddrMValue)
      val roadway1      = toRoadway(rightLinks).copy(track = leftTrackAddresses.head.track, startAddrMValue = combinedTrackAddresses.max.endAddrMValue, endAddrMValue = leftTrackAddresses.max.endAddrMValue)
      val roadway2      = toRoadway(leftLinks).copy(track = rightTrackAddresses.head.track, startAddrMValue = combinedTrackAddresses.max.endAddrMValue, endAddrMValue = rightTrackAddresses.max.endAddrMValue)

      roadwayDAO.create(Seq(roadway0, roadway1, roadway2))

      val partitions = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(projectLinks, projectLinks)
      val sectionPairs = partitions.adjustedSections.zip(partitions.originalSections)

      sectionPairs should have size 3
      val combined = sectionPairs.find(_._1.track == Track.Combined)
      val track1   = sectionPairs.find(_._1.track == Track.RightSide)
      val track2   = sectionPairs.find(_._1.track == Track.LeftSide)

      combined.size should be(1)
      track1.size should be(1)
      track2.size should be(1)

      combined.map(addressTrackChanges) should be(Some((getMinAddress(combinedLinks), getMinAddress(combinedTrackAddresses), getMaxAddress(combinedLinks), getMaxAddress(combinedTrackAddresses), Track.Combined,  Track.Combined)))
      track1.map(addressTrackChanges)   should be(Some((getMinAddress(rightLinks),    getMinAddress(rightTrackAddresses),    getMaxAddress(rightLinks),    getMaxAddress(rightTrackAddresses),    Track.RightSide, Track.LeftSide)))
      track2.map(addressTrackChanges)   should be(Some((getMinAddress(leftLinks),     getMinAddress(leftTrackAddresses),     getMaxAddress(leftLinks),     getMaxAddress(leftTrackAddresses),     Track.LeftSide,  Track.RightSide)))
    }
  }

  test("Test ProjectDeltaCalculator.partition " +
                "When a road with a combined part + two track part where other track is terminated" +
                "Then returns the correct From RoadSection -> To RoadSection mapping.") {
    implicit val ordering: Ordering[RoadAddress] = Ordering.by(_.endAddrMValue)
    def getMinAddress(pls: Seq[BaseRoadAddress]): Long = pls.minBy(_.startAddrMValue).startAddrMValue
    def getMaxAddress(pls: Seq[BaseRoadAddress]): Long = pls.maxBy(_.endAddrMValue).endAddrMValue
    def addressTrackChanges(x: (RoadwaySection, RoadwaySection)): (Long, Long, Long, Long, Track, Track) = (x._1.startMAddr, x._2.startMAddr, x._1.endMAddr, x._2.endMAddr, x._1.track, x._2.track)
    def toProjectLinks(transferLinks: IndexedSeq[(RoadAddress, ProjectLink)], track: Track)(implicit addresses: Seq[RoadAddress]): IndexedSeq[ProjectLink] = {
      val roadwayId = transferLinks.head._2.roadwayId
      transferLinks.map(l => {
        l._2.copy(track = track, roadwayId = roadwayId)
      })
    }
    runWithRollback {
      val distance = 10L
      val combinedTrackAddresses  = (0 to 10).map(f = i => {
        createRoadAddress(i * 10, distance)
      })
      val leftTrackAddresses = (11 to 21).map(i => {
        createRoadAddress(i * 10, distance)
      }).map(f = a => {a.copy(track = Track.LeftSide)})
      implicit val rightTrackAddresses: immutable.IndexedSeq[RoadAddress] = (11 to 21).map(i => {
        createRoadAddress(i * 10, distance)
      }).map(f = a => a.copy(id = a.id + 1, track = Track.RightSide))

      val transferLinks0 = combinedTrackAddresses.map(toTransition(project, LinkStatus.UnChanged))
      val transferLinks1 = rightTrackAddresses.map(toTransition(project, LinkStatus.Terminated))
      val transferLinks2 = leftTrackAddresses.map(toTransition(project, LinkStatus.Transfer))

      val projectLinks = toProjectLinks(transferLinks0, Track.Combined) ++ toProjectLinks(transferLinks1, Track.RightSide) ++ toProjectLinks(transferLinks2, Track.Combined)

      val (combinedLinks, leftLinks) = projectLinks.filter(_.track == Track.Combined).partition(_.endAddrMValue < 120)
      val rightLinks    = projectLinks.filter(_.track == Track.RightSide)

      val roadway0      = toRoadway(combinedLinks).copy(track = combinedTrackAddresses.head.track, startAddrMValue = 0, endAddrMValue = combinedTrackAddresses.max.endAddrMValue)
      val roadway1      = toRoadway(leftLinks).copy(track = leftTrackAddresses.head.track, startAddrMValue = combinedTrackAddresses.max.endAddrMValue, endAddrMValue = leftTrackAddresses.max.endAddrMValue)
      val roadway2      = toRoadway(rightLinks).copy(track = rightTrackAddresses.head.track, startAddrMValue = combinedTrackAddresses.max.endAddrMValue, endAddrMValue = rightTrackAddresses.max.endAddrMValue)

      roadwayDAO.create(Seq(roadway0, roadway1, roadway2))

      val partitions = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(projectLinks, projectLinks)
      val sectionPairs = partitions.adjustedSections.zip(partitions.originalSections)

      sectionPairs should have size 3
      val combined = sectionPairs.find(_._2.track == Track.Combined)
      val track1   = sectionPairs.find(_._2.track == Track.RightSide)
      val track2   = sectionPairs.find(_._2.track == Track.LeftSide)

      combined.size should be(1)
      track1.size should be(1)
      track2.size should be(1)

      combined.map(addressTrackChanges) should be(Some((getMinAddress(combinedLinks), getMinAddress(combinedTrackAddresses), getMaxAddress(combinedLinks), getMaxAddress(combinedTrackAddresses), Track.Combined,  Track.Combined)))
      track1.map(addressTrackChanges)   should be(Some((getMinAddress(rightLinks),    getMinAddress(rightTrackAddresses),    getMaxAddress(rightLinks),    getMaxAddress(rightTrackAddresses),    Track.RightSide, Track.RightSide)))
      track2.map(addressTrackChanges)   should be(Some((getMinAddress(leftLinks),     getMinAddress(leftTrackAddresses),     getMaxAddress(leftLinks),     getMaxAddress(leftTrackAddresses),     Track.Combined,  Track.LeftSide)))
    }
  }

  test("Test ProjectDeltaCalculator.partition When executing a numbering operation on single road part Then returns the correct From RoadSection -> To RoadSection mapping with the new road number/road part number.") {
    runWithRollback {
      val addresses      = (0 to 10).map(i => {
        createRoadAddress(i * 10, 10L)
      })
      val numberingLinks = addresses.map(a => {
        (a, a.copy(roadNumber = 12345, roadPartNumber = 1))
      }).map(x => {
        (x._1, toProjectLink(project, LinkStatus.Numbering)(x._2).copy(roadwayId = 0))
      })

      val roadway205 = toRoadway(numberingLinks.map(_._2).map(_.copy(roadNumber = 5, roadPartNumber = 205)))
      roadwayDAO.create(Seq(roadway205))
      val unchangedParts2 = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(numberingLinks.map(_._2), numberingLinks.map(_._2))
      val unchangedParts3 = unchangedParts2.adjustedSections.zip(unchangedParts2.originalSections)

      unchangedParts3 should have size 1
      val correctRoadNumber     = unchangedParts3.find(p => {
        p._2.roadNumber == 5 && p._1.roadNumber == 12345
      })
      val correctRoadPartNumber = unchangedParts3.find(p => {
        p._2.roadPartNumberStart == 205 && p._2.roadPartNumberEnd == 205 && p._1.roadPartNumberStart == 1 && p._1.roadPartNumberEnd == 1
      })
      correctRoadNumber.size should be(1)
      correctRoadPartNumber.size should be(1)

      correctRoadNumber.get._1.track should be(correctRoadNumber.get._2.track)
      correctRoadNumber.get._1.discontinuity should be(correctRoadNumber.get._2.discontinuity)
      correctRoadNumber.map(x => {
        (x._1.startMAddr, x._2.startMAddr, x._1.endMAddr, x._2.endMAddr)
      }) should be(Some((0L, 0L, 110L, 110L)))
    }
  }

  test("Test ProjectDeltaCalculator.partition " +
       "When a two track road is terminated from first links and rest is tranferred" +
       "Then returns the correct From RoadSection -> To RoadSection mapping.") {
    runWithRollback {
      def plId: Long = Sequences.nextProjectLinkId
      val allProjectLinks = Seq(
        ProjectLink(plId,1999,1,RightSide,Continuous,0,100,0,100,None,None,Some("test_user"),1286532,0.0,100.0,TowardsDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,NoCP),List(Point(0.0,0.0,0.0), Point(0.0,100.0,0.0)),1227332,Terminated,AdministrativeClass.State,NormalLinkInterface,100.0,1316836,0,8,false,None,0,1000000000,None,None,None,None,None,None,None),
        ProjectLink(plId,1999,1,RightSide,Continuous,100,210,100,210,None,None,Some("test_user"),1286533,0.0,110.0,TowardsDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,NoCP),List(Point(0.0,100.0,0.0), Point(0.0,210.0,0.0)),1227332,Terminated,AdministrativeClass.State,NormalLinkInterface,110.0,1316836,0,8,false,None,0,1000000000,None,None,None,None,None,None,None),
        ProjectLink(plId,1999,1,LeftSide,Continuous,0,200,0,200,None,None,Some("test_user"),1286538,0.0,200.0,TowardsDigitizing,(RoadAddressCP,NoCP),(RoadAddressCP,NoCP),List(Point(5.0,0.0,0.0), Point(5.0,200.0,0.0)),1227332,Terminated,AdministrativeClass.State,NormalLinkInterface,200.0,1316838,0,8,false,None,0,1000000001,None,None,None,None,None,None,None),

        ProjectLink(plId,1999,1,RightSide,Continuous,0,210,210,420,None,None,Some("test_user"),1286434,0.0,210.0,TowardsDigitizing,(RoadAddressCP,NoCP),(NoCP,NoCP),List(Point(0.0,200.0,0.0), Point(0.0,400.0,0.0)),1227332,Transfer,AdministrativeClass.State,NormalLinkInterface,200.0,1316836,0,8,false,None,0,1417932,None,None,None,None,None,None,None),
        ProjectLink(plId,1999,1,RightSide,Continuous,210,420,420,630,None,None,Some("test_user"),1286435,0.0,210.0,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(0.0,400.0,0.0), Point(0.0,600.0,0.0)),1227332,Transfer,AdministrativeClass.State,NormalLinkInterface,200.0,1316836,0,8,false,None,0,1417932,None,None,None,None,None,None,None),
        ProjectLink(plId,1999,1,RightSide,Continuous,420,590,630,800,None,None,Some("test_user"),1286436,0.0,161.9047619047619,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(0.0,600.0,0.0), Point(0.0,761.905,0.0)),1227332,Transfer,AdministrativeClass.State,NormalLinkInterface,161.9047619047619,1316836,0,8,false,Some(1286436),0,1417932,None,None,None,None,None,None,None),
        ProjectLink(plId,1999,1,RightSide,Continuous,590,630,800,840,None,None,Some("test_user"),1286436,161.9047619047619,210.0,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(0.0,761.905,0.0), Point(0.0,800.0,0.0)),1227332,Transfer,AdministrativeClass.State,NormalLinkInterface,38.0952380952381,1316836,0,8,false,Some(1286436),0,1417932,None,None,None,None,None,None,None),
        ProjectLink(plId,1999,1,RightSide,EndOfRoad, 630,795,840,1000,None,None,Some("test_user"),1286437,0.0,210.0,TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,NoCP),List(Point(0.0,800.0,0.0), Point(0.0,1000.0,0.0)),1227332,Transfer,AdministrativeClass.State,NormalLinkInterface,200.0,1316836,0,8,false,None,0,1417932,None,None,None,None,None,None,None),

        ProjectLink(plId,1999,1,LeftSide,Continuous,0,10,200,210,None,None,Some("test_user"),1286438,0.0,10.0,TowardsDigitizing,(RoadAddressCP,NoCP),(NoCP,NoCP),List(Point(5.0,200.0,0.0), Point(5.0,210.0,0.0)),1227332,Transfer,AdministrativeClass.State,NormalLinkInterface,10.0,1316838,0,8,false,None,0,1000000001,None,None,None,None,None,None,None),
        ProjectLink(plId,1999,1,LeftSide,Continuous,10,200,210,400,None,None,Some("test_user"),1286438,10.0,200.0,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(5.0,210.0,0.0), Point(5.0,400.0,0.0)),1227332,Transfer,AdministrativeClass.State,NormalLinkInterface,190.0,1316838,0,8,false,None,0,1000000001,None,None,None,None,None,None,None),
        ProjectLink(plId,1999,1,LeftSide,Continuous,200,400,400,600,None,None,Some("test_user"),1286440,0.0,200.0,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(5.0,400.0,0.0), Point(5.0,600.0,0.0)),1227332,Transfer,AdministrativeClass.State,NormalLinkInterface,200.0,1316838,0,8,false,None,0,1000000001,None,None,None,None,None,None,None),
        ProjectLink(plId,1999,1,LeftSide,Continuous,400,600,600,800,None,None,Some("test_user"),1286441,0.0,200.0,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(5.0,600.0,0.0), Point(5.0,800.0,0.0)),1227332,Transfer,AdministrativeClass.State,NormalLinkInterface,200.0,1316838,0,8,false,None,0,1000000001,None,None,None,None,None,None,None),
        ProjectLink(plId,1999,1,LeftSide,Continuous,600,640,800,840,None,None,Some("test_user"),1286442,0.0,40.0,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(5.0,800.0,0.0), Point(5.0,840.0,0.0)),1227332,Transfer,AdministrativeClass.State,NormalLinkInterface,40.0,1316838,0,8,false,Some(1286442),0,1000000001,None,None,None,None,None,None,None),
        ProjectLink(plId,1999,1,LeftSide,EndOfRoad, 640,795,840,1000,None,None,Some("test_user"),1286442,40.0,200.0,TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,NoCP),List(Point(5.0,840.0,0.0), Point(5.0,1000.0,0.0)),1227332,Transfer,AdministrativeClass.State,NormalLinkInterface,160.0,1316838,0,8,false,Some(1286442),0,1000000001,None,None,None,None,None,None,None)
      )

      val transferred = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(allProjectLinks.filter(_.status != LinkStatus.Terminated), allProjectLinks)
      val transferredPaired = transferred.adjustedSections.zip(transferred.originalSections)

      val terminated = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(allProjectLinks.filter(_.status == LinkStatus.Terminated), allProjectLinks)

      val twoTrackOldAddressRoadParts = createTwoTrackOldAddressRoadParts(Seq(),transferredPaired,terminated)
      val oldRoadTwoTrackParts = ProjectDeltaCalculator.matchTerminatedRoadwaySections(twoTrackOldAddressRoadParts)

      val twoTrackAdjustedTerminated = oldRoadTwoTrackParts.flatMap(_._1) ++ oldRoadTwoTrackParts.flatMap(_._2)
      val combinedTerminatedTrack = terminated.adjustedSections.filter(_.track == Track.Combined)

      val adjustedTerminated = combinedTerminatedTrack ++ twoTrackAdjustedTerminated

      transferredPaired should have size 2
      adjustedTerminated should have size 2

      transferredPaired.map(x => {
        (x._1.startMAddr, x._2.startMAddr, x._1.endMAddr, x._2.endMAddr)
      }).foreach(_ should be((0L, 205L, 795L, 1000L)))

      adjustedTerminated.map(x => {
        (x.startMAddr, x.endMAddr)
      }).foreach(_ should be((0L, 205L)))
    }
  }

  test("Test ProjectDeltaCalculator.partition " +
       "When two single track roads are tranferred to the same roadnumber and roadpartnumber having terminated links " +
       "Then returns the correct From RoadSection -> To RoadSection mapping.") {
    runWithRollback {
      val roadNumber = 7622
      val roadPartNumber = 1
      val createdBy = "Test"
      val roadName = None
      val projectId = Sequences.nextViiteProjectId

      roadwayDAO.create(Seq(
        Roadway(30701,64686,18385,1,AdministrativeClass.State,Track.Combined,Discontinuity.Continuous,0,2568,reversed = false,DateTime.parse("1933-01-01T00:00:00.000+02:00"),None,createdBy,roadName,12,TerminationCode.NoTermination,DateTime.parse("1998-10-16T00:00:00.000+03:00"),None),
        Roadway(30920,64355,7622,1,AdministrativeClass.State,Track.Combined,Discontinuity.Continuous,0,1400,reversed = false,DateTime.parse("1967-01-01T00:00:00.000+02:00"),None,createdBy,roadName,12,TerminationCode.NoTermination,DateTime.parse("1998-10-16T00:00:00.000+03:00"),None),
        Roadway(30993,64687,18385,1,AdministrativeClass.State,Track.Combined,Discontinuity.EndOfRoad,2568,4403,reversed = false,DateTime.parse("1901-01-01T00:00:00.000+01:39:49"),None,createdBy,roadName,12,TerminationCode.NoTermination,DateTime.parse("1998-10-16T00:00:00.000+03:00"),None),
        Roadway(31341,64356,7622,1,AdministrativeClass.State,Track.Combined,Discontinuity.Continuous,1400,4828,reversed = false,DateTime.parse("1952-01-01T00:00:00.000+02:00"),None,createdBy,roadName,12,TerminationCode.NoTermination,DateTime.parse("1998-10-16T00:00:00.000+03:00"),None)
      ))

      val allProjectLinks = Seq(
        ProjectLink(1000,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,0,18,0,18,None,None,Some(createdBy),243576,0.0,17.829,AgainstDigitizing,(JunctionPointCP,NoCP),(JunctionPointCP,NoCP),List(Point(418449.0,7069399.0,0.0), Point(418450.0,7069417.0,0.0)),projectId,LinkStatus.Terminated,AdministrativeClass.State,FrozenLinkInterface,17.829,30920,258599,12,false,None,1633993775000L,335718902,roadName,None,None,None,None,None,None),
        ProjectLink(1001,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,18,81,18,81,None,None,Some(createdBy),243612,0.0,63.291,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(418453.0,7069336.0,0.0), Point(418449.0,7069399.0,0.0)),projectId,LinkStatus.Terminated,AdministrativeClass.State,FrozenLinkInterface,63.291,30920,258600,12,false,None,1633993775000L,335718902,roadName,None,None,None,None,None,None),
        ProjectLink(1002,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,0,143,0,143,None,None,Some(createdBy),247382,0.0,142.727,TowardsDigitizing,(RoadAddressCP,NoCP),(JunctionPointCP,NoCP),List(Point(416275.0,7065372.0,0.0), Point(416398.0,7065442.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,142.727,30701,255183,12,false,None,1446398762000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1003,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,143,193,143,193,None,None,Some(createdBy),247385,0.0,49.552,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(416398.0,7065442.0,0.0), Point(416430.0,7065479.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,49.552,30701,255184,12,false,None,1446398762000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1004,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,81,209,81,209,None,None,Some(createdBy),243607,0.0,127.544,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(418468.0,7069209.0,0.0), Point(418453.0,7069336.0,0.0)),projectId,LinkStatus.Terminated,AdministrativeClass.State,FrozenLinkInterface,127.544,30920,258601,12,false,None,1446398762000L,335718902,roadName,None,None,None,None,None,None),
        ProjectLink(1005,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,209,322,209,322,None,None,Some(createdBy),243603,0.0,113.171,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(418489.0,7069098.0,0.0), Point(418468.0,7069209.0,0.0)),projectId,LinkStatus.Terminated,AdministrativeClass.State,FrozenLinkInterface,113.171,30920,258602,12,false,None,1446398762000L,335718902,roadName,None,None,None,None,None,None),
        ProjectLink(1006,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,193,439,193,439,None,None,Some(createdBy),247383,0.0,245.943,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(416430.0,7065479.0,0.0), Point(416543.0,7065698.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,245.943,30701,255185,12,false,None,1446398762000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1007,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,439,483,439,483,None,None,Some(createdBy),247373,0.0,44.291,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(416543.0,7065698.0,0.0), Point(416564.0,7065737.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,44.291,30701,255186,12,false,None,1446398762000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1008,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,483,512,483,512,None,None,Some(createdBy),247371,0.0,28.553,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(416564.0,7065737.0,0.0), Point(416576.0,7065763.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,28.553,30701,255187,12,false,None,1446398762000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1009,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,512,541,512,541,None,None,Some(createdBy),247369,0.0,29.658,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(416576.0,7065763.0,0.0), Point(416590.0,7065789.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,29.658,30701,255188,12,false,None,1446398762000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1010,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,541,570,541,570,None,None,Some(createdBy),247367,0.0,28.408,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(416590.0,7065789.0,0.0), Point(416603.0,7065814.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,28.408,30701,255189,12,false,None,1446398762000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1011,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,570,682,570,682,None,None,Some(createdBy),247366,0.0,111.875,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(416603.0,7065814.0,0.0), Point(416654.0,7065914.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,111.875,30701,255190,12,false,None,1446398762000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1012,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,682,747,682,747,None,None,Some(createdBy),10949305,0.0,64.611,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(416654.0,7065914.0,0.0), Point(416684.0,7065971.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,64.611,30701,255191,12,false,None,1537225220000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1013,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,322,762,322,762,None,None,Some(createdBy),246868,0.0,438.103,AgainstDigitizing,(NoCP,JunctionPointCP),(NoCP,JunctionPointCP),List(Point(418647.0,7068691.0,0.0), Point(418489.0,7069098.0,0.0)),projectId,LinkStatus.Terminated,AdministrativeClass.State,FrozenLinkInterface,438.103,30920,258603,12,false,None,1491001212000L,335718902,roadName,None,None,None,None,None,None),
        ProjectLink(1014,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,747,838,747,838,None,None,Some(createdBy),247328,0.0,90.718,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(416684.0,7065971.0,0.0), Point(416725.0,7066052.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,90.718,30701,255192,12,false,None,1446398762000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1015,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,838,1000,838,1000,None,None,Some(createdBy),247320,0.0,161.88,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(416725.0,7066052.0,0.0), Point(416798.0,7066196.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,161.88,30701,255193,12,false,None,1446398762000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1016,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,1000,1049,1000,1049,None,None,Some(createdBy),247322,0.0,49.105,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(416798.0,7066196.0,0.0), Point(416821.0,7066240.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,49.105,30701,255194,12,false,None,1446398762000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1017,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,1049,1128,1049,1128,None,None,Some(createdBy),247304,0.0,79.434,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(416821.0,7066240.0,0.0), Point(416857.0,7066311.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,79.434,30701,255195,12,false,None,1446398762000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1018,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,1128,1168,1128,1168,None,None,Some(createdBy),247312,0.0,39.837,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(416857.0,7066311.0,0.0), Point(416875.0,7066346.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,39.837,30701,255196,12,false,None,1446398762000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1019,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,1168,1346,1168,1346,None,None,Some(createdBy),247308,0.0,177.111,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(416875.0,7066346.0,0.0), Point(416955.0,7066504.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,177.111,30701,255197,12,false,None,1446398762000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1020,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,1346,1388,1346,1388,None,None,Some(createdBy),7492578,0.0,42.837,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(416955.0,7066504.0,0.0), Point(416974.0,7066543.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,42.837,30701,255198,12,false,None,1510836157000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1021,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,1388,1465,1388,1465,None,None,Some(createdBy),7492577,0.0,76.487,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(416974.0,7066543.0,0.0), Point(417009.0,7066611.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,76.487,30701,255199,12,false,None,1510836157000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1022,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,1465,1618,1465,1618,None,None,Some(createdBy),247301,0.0,153.109,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(417009.0,7066611.0,0.0), Point(417078.0,7066747.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,153.109,30701,255200,12,false,None,1446398762000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1023,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,1618,1812,1618,1812,None,None,Some(createdBy),247295,0.0,193.047,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(417078.0,7066747.0,0.0), Point(417166.0,7066919.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,193.047,30701,255201,12,false,None,1446398762000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1024,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,1812,1964,1812,1964,None,None,Some(createdBy),247288,0.0,151.899,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(417166.0,7066919.0,0.0), Point(417235.0,7067054.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,151.899,30701,255202,12,false,None,1446398762000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1025,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,1964,2006,1964,2006,None,None,Some(createdBy),247140,0.0,42.223,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(417235.0,7067054.0,0.0), Point(417254.0,7067092.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,42.223,30701,255203,12,false,None,1446398762000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1026,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,2006,2334,2006,2334,None,None,Some(createdBy),246927,0.0,327.804,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(417254.0,7067092.0,0.0), Point(417402.0,7067385.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,327.804,30701,255204,12,false,None,1446398762000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1027,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,2334,2484,2334,2484,None,None,Some(createdBy),247016,0.0,149.69,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(417402.0,7067385.0,0.0), Point(417468.0,7067519.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,149.69,30701,255205,12,false,None,1446398762000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1028,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,2484,2568,2484,2568,None,None,Some(createdBy),246989,0.0,83.589,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(417468.0,7067519.0,0.0), Point(417502.0,7067595.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,83.589,30701,255206,12,false,None,1446398762000L,64686,roadName,None,None,None,None,None,None),
        ProjectLink(1029,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,2568,2569,2568,2569,None,None,Some(createdBy),246989,83.589,84.596,TowardsDigitizing,(NoCP,JunctionPointCP),(NoCP,JunctionPointCP),List(Point(417502.0,7067595.0,0.0), Point(417502.0,7067596.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,1.007,30993,259608,12,false,None,1446398762000L,64687,roadName,None,None,None,None,None,None),
        ProjectLink(1030,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,2569,2715,2569,2715,None,None,Some(createdBy),246999,0.0,145.708,TowardsDigitizing,(JunctionPointCP,NoCP),(JunctionPointCP,NoCP),List(Point(417502.0,7067596.0,0.0), Point(417501.0,7067731.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,145.708,30993,259609,12,false,None,1446398762000L,64687,roadName,None,None,None,None,None,None),
        ProjectLink(1031,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,2715,2757,2715,2757,None,None,Some(createdBy),246997,0.0,41.556,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(417501.0,7067731.0,0.0), Point(417497.0,7067772.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,41.556,30993,259610,12,false,None,1446398762000L,64687,roadName,None,None,None,None,None,None),
        ProjectLink(1032,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,2757,2875,2757,2875,None,None,Some(createdBy),246995,0.0,117.846,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(417497.0,7067772.0,0.0), Point(417504.0,7067890.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,117.846,30993,259611,12,false,None,1446398762000L,64687,roadName,None,None,None,None,None,None),
        ProjectLink(1033,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,2875,2881,2875,2881,None,None,Some(createdBy),246949,0.0,6.251,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(417504.0,7067890.0,0.0), Point(417504.0,7067896.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,6.251,30993,259612,12,false,None,1446398762000L,64687,roadName,None,None,None,None,None,None),
        ProjectLink(1034,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,2881,2963,2881,2963,None,None,Some(createdBy),246945,0.0,82.163,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(417504.0,7067896.0,0.0), Point(417509.0,7067978.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,82.163,30993,259613,12,false,None,1446398762000L,64687,roadName,None,None,None,None,None,None),
        ProjectLink(1035,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,2963,3023,2963,3023,None,None,Some(createdBy),246948,0.0,60.1,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(417509.0,7067978.0,0.0), Point(417513.0,7068038.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,60.1,30993,259614,12,false,None,1446398762000L,64687,roadName,None,None,None,None,None,None),
        ProjectLink(1036,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,3023,3039,3023,3039,None,None,Some(createdBy),246944,0.0,15.151,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(417513.0,7068038.0,0.0), Point(417519.0,7068052.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,15.151,30993,259615,12,false,None,1446398762000L,64687,roadName,None,None,None,None,None,None),
        ProjectLink(1037,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,3039,3146,3039,3146,None,None,Some(createdBy),246932,0.0,107.314,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(417519.0,7068052.0,0.0), Point(417609.0,7068111.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,107.314,30993,259616,12,false,None,1446398762000L,64687,roadName,None,None,None,None,None,None),
        ProjectLink(1038,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,3146,3280,3146,3280,None,None,Some(createdBy),246939,0.0,134.064,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(417609.0,7068111.0,0.0), Point(417715.0,7068193.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,134.064,30993,259617,12,false,None,1491001212000L,64687,roadName,None,None,None,None,None,None),
        ProjectLink(1039,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,3280,3317,3280,3317,None,None,Some(createdBy),246940,0.0,37.065,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(417715.0,7068193.0,0.0), Point(417745.0,7068215.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,37.065,30993,259618,12,false,None,1491001212000L,64687,roadName,None,None,None,None,None,None),
        ProjectLink(1040,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,3317,3423,3317,3423,None,None,Some(createdBy),246935,0.0,105.728,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(417745.0,7068215.0,0.0), Point(417845.0,7068247.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,105.728,30993,259619,12,false,None,1491001212000L,64687,roadName,None,None,None,None,None,None),
        ProjectLink(1041,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,3423,3841,3423,3841,None,None,Some(createdBy),246930,0.0,417.176,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(417845.0,7068247.0,0.0), Point(418154.0,7068482.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,417.176,30993,259620,12,false,None,1491001212000L,64687,roadName,None,None,None,None,None,None),
        ProjectLink(1042,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,3841,4033,3841,4033,None,None,Some(createdBy),246913,0.0,191.134,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(418154.0,7068482.0,0.0), Point(418292.0,7068607.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,191.134,30993,259621,12,false,None,1446398762000L,64687,roadName,None,None,None,None,None,None),
        ProjectLink(1043,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,4033,4111,4033,4111,None,None,Some(createdBy),246920,0.0,78.189,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(418292.0,7068607.0,0.0), Point(418364.0,7068638.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,78.189,30993,259622,12,false,None,1446398762000L,64687,roadName,None,None,None,None,None,None),
        ProjectLink(1044,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,4111,4337,4111,4337,None,None,Some(createdBy),246919,0.0,225.819,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(418364.0,7068638.0,0.0), Point(418583.0,7068677.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,225.819,30993,259623,12,false,None,1481324409000L,64687,roadName,None,None,None,None,None,None),
        ProjectLink(1045,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,4337,4385,4337,4385,None,None,Some(createdBy),246898,0.0,47.536,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(418583.0,7068677.0,0.0), Point(418630.0,7068684.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,47.536,30993,259624,12,false,None,1446398762000L,64687,roadName,None,None,None,None,None,None),
        ProjectLink(1046,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,4385,4403,4385,4403,None,None,Some(createdBy),246900,0.0,18.108,TowardsDigitizing,(NoCP,JunctionPointCP),(NoCP,JunctionPointCP),List(Point(418630.0,7068684.0,0.0), Point(418647.0,7068691.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,18.108,30993,259625,12,false,None,1491001212000L,64687,roadName,None,None,None,None,None,None),
        ProjectLink(1047,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,4403,4486,762,845,None,None,Some(createdBy),246893,0.0,82.953,AgainstDigitizing,(JunctionPointCP,NoCP),(JunctionPointCP,NoCP),List(Point(418688.0,7068619.0,0.0), Point(418647.0,7068691.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,82.953,30920,258604,12,false,None,1491001212000L,335718897,roadName,None,None,None,None,None,None),
        ProjectLink(1048,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,4486,4532,845,891,None,None,Some(createdBy),246903,0.0,46.06,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(418711.0,7068579.0,0.0), Point(418688.0,7068619.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,46.06,30920,258605,12,false,None,1491001212000L,335718897,roadName,None,None,None,None,None,None),
        ProjectLink(1049,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,4532,4623,891,982,None,None,Some(createdBy),246895,0.0,90.05,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(418756.0,7068501.0,0.0), Point(418711.0,7068579.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,90.05,30920,258606,12,false,None,1491001212000L,335718897,roadName,None,None,None,None,None,None),
        ProjectLink(1050,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,4623,4705,982,1064,None,None,Some(createdBy),246905,0.0,82.11,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(418795.0,7068428.0,0.0), Point(418756.0,7068501.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,82.11,30920,258607,12,false,None,1491001212000L,335718897,roadName,None,None,None,None,None,None),
        ProjectLink(1051,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,4705,4866,1064,1225,None,None,Some(createdBy),247023,0.0,160.277,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(418854.0,7068280.0,0.0), Point(418795.0,7068428.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,160.277,30920,258608,12,false,None,1491001212000L,335718897,roadName,None,None,None,None,None,None),
        ProjectLink(1052,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,4866,5039,1225,1398,None,None,Some(createdBy),10954075,0.0,172.775,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(418908.0,7068115.0,0.0), Point(418854.0,7068280.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,172.775,30920,258609,12,false,None,1537230701000L,335718897,roadName,None,None,None,None,None,None),
        ProjectLink(1053,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,5039,5041,1398,1400,None,None,Some(createdBy),10954076,108.116,110.118,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(418908.0,7068113.0,0.0), Point(418908.0,7068115.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,2.002,30920,258610,12,false,None,1537230701000L,335718897,roadName,None,None,None,None,None,None),
        ProjectLink(1054,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,5041,5149,1400,1508,None,None,Some(createdBy),10954076,0.0,108.116,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(418941.0,7068010.0,0.0), Point(418908.0,7068113.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,108.116,31341,265145,12,false,None,1537230701000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1055,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,5149,5225,1508,1584,None,None,Some(createdBy),247036,0.0,75.156,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(418964.0,7067939.0,0.0), Point(418941.0,7068010.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,75.156,31341,265146,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1056,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,5225,5258,1584,1617,None,None,Some(createdBy),247039,0.0,33.651,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(418974.0,7067907.0,0.0), Point(418964.0,7067939.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,33.651,31341,265147,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1057,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,5258,5391,1617,1750,None,None,Some(createdBy),247054,0.0,132.283,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419014.0,7067781.0,0.0), Point(418974.0,7067907.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,132.283,31341,265148,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1058,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,5391,5498,1750,1857,None,None,Some(createdBy),247052,0.0,106.948,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419046.0,7067679.0,0.0), Point(419014.0,7067781.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,106.948,31341,265149,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1059,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,5498,5567,1857,1926,None,None,Some(createdBy),247056,0.0,68.855,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419068.0,7067613.0,0.0), Point(419046.0,7067679.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,68.855,31341,265150,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1060,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,5567,5669,1926,2028,None,None,Some(createdBy),247047,0.0,101.123,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419098.0,7067517.0,0.0), Point(419068.0,7067613.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,101.123,31341,265151,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1061,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,5669,5754,2028,2113,None,None,Some(createdBy),247057,0.0,85.278,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419124.0,7067436.0,0.0), Point(419098.0,7067517.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,85.278,31341,265152,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1062,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,5754,5818,2113,2177,None,None,Some(createdBy),247058,0.0,64.169,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419140.0,7067373.0,0.0), Point(419124.0,7067436.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,64.169,31341,265153,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1063,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,5818,5941,2177,2300,None,None,Some(createdBy),247060,0.0,122.064,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419156.0,7067252.0,0.0), Point(419140.0,7067373.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,122.064,31341,265154,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1064,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,5941,5983,2300,2342,None,None,Some(createdBy),247046,0.0,42.131,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419161.0,7067211.0,0.0), Point(419156.0,7067252.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,42.131,31341,265155,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1065,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,5983,6016,2342,2375,None,None,Some(createdBy),247080,0.0,33.262,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419165.0,7067178.0,0.0), Point(419161.0,7067211.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,33.262,31341,265156,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1066,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,6016,6038,2375,2397,None,None,Some(createdBy),247079,0.0,21.343,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419168.0,7067156.0,0.0), Point(419165.0,7067178.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,21.343,31341,265157,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1067,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,6038,6099,2397,2458,None,None,Some(createdBy),247077,0.0,61.294,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419176.0,7067096.0,0.0), Point(419168.0,7067156.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,61.294,31341,265158,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1068,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,6099,6124,2458,2483,None,None,Some(createdBy),247081,0.0,25.136,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419181.0,7067071.0,0.0), Point(419176.0,7067096.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,25.136,31341,265159,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1069,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,6124,6314,2483,2673,None,None,Some(createdBy),247093,0.0,188.847,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419271.0,7066906.0,0.0), Point(419181.0,7067071.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,188.847,31341,265160,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1070,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,6314,6461,2673,2820,None,None,Some(createdBy),247102,0.0,147.263,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419349.0,7066782.0,0.0), Point(419271.0,7066906.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,147.263,31341,265161,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1071,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,6461,6549,2820,2908,None,None,Some(createdBy),247100,0.0,87.254,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419387.0,7066703.0,0.0), Point(419349.0,7066782.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,87.254,31341,265162,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1072,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,6549,6694,2908,3053,None,None,Some(createdBy),247103,0.0,144.828,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419448.0,7066572.0,0.0), Point(419387.0,7066703.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,144.828,31341,265163,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1073,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,6694,6966,3053,3325,None,None,Some(createdBy),10952119,0.0,271.447,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419565.0,7066326.0,0.0), Point(419448.0,7066572.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,271.447,31341,265164,12,false,None,1537228663000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1074,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,6966,7005,3325,3364,None,None,Some(createdBy),247115,0.0,38.501,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419580.0,7066291.0,0.0), Point(419565.0,7066326.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,38.501,31341,265165,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1075,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,7005,7312,3364,3671,None,None,Some(createdBy),10952120,0.0,306.756,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419625.0,7065988.0,0.0), Point(419580.0,7066291.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,306.756,31341,265166,12,false,None,1537228663000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1076,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,7312,7380,3671,3739,None,None,Some(createdBy),247125,0.0,67.312,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419634.0,7065921.0,0.0), Point(419625.0,7065988.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,67.312,31341,265167,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1077,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,7380,7433,3739,3792,None,None,Some(createdBy),247449,0.0,52.982,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419644.0,7065869.0,0.0), Point(419634.0,7065921.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,52.982,31341,265168,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1078,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,7433,7594,3792,3953,None,None,Some(createdBy),247446,0.0,160.97,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419706.0,7065721.0,0.0), Point(419644.0,7065869.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,160.97,31341,265169,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1079,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,7594,7743,3953,4102,None,None,Some(createdBy),247439,0.0,148.28,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419739.0,7065578.0,0.0), Point(419706.0,7065721.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,148.28,31341,265170,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1080,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,7743,7924,4102,4283,None,None,Some(createdBy),247460,0.0,180.89,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419761.0,7065398.0,0.0), Point(419739.0,7065578.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,180.89,31341,265171,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1081,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,7924,7990,4283,4349,None,None,Some(createdBy),247462,0.0,65.605,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419762.0,7065333.0,0.0), Point(419761.0,7065398.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,65.605,31341,265172,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1082,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,7990,8206,4349,4565,None,None,Some(createdBy),10952140,0.0,215.81,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419852.0,7065147.0,0.0), Point(419762.0,7065333.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,215.81,31341,265173,12,false,None,1537228663000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1083,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,8206,8277,4565,4636,None,None,Some(createdBy),248468,0.0,70.731,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419913.0,7065112.0,0.0), Point(419852.0,7065147.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,70.731,31341,265174,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1084,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,8277,8301,4636,4660,None,None,Some(createdBy),248469,0.0,23.829,AgainstDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(419933.0,7065099.0,0.0), Point(419913.0,7065112.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,23.829,31341,265175,12,false,None,1446398762000L,64356,roadName,None,None,None,None,None,None),
        ProjectLink(1085,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,8301,8469,4660,4828,None,None,Some(createdBy),10949334,0.0,167.624,AgainstDigitizing,(NoCP,RoadAddressCP),(NoCP,JunctionPointCP),List(Point(420026.0,7064963.0,0.0), Point(419933.0,7065099.0,0.0)),projectId,LinkStatus.Transfer,AdministrativeClass.State,FrozenLinkInterface,167.624,31341,265176,12,false,None,1537225220000L,64356,roadName,None,None,None,None,None,None)
      )

      val transferred = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(allProjectLinks.filter(_.status != LinkStatus.Terminated), allProjectLinks)
      val transferredPaired = transferred.adjustedSections.zip(transferred.originalSections)

      val terminated = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(allProjectLinks.filter(_.status == LinkStatus.Terminated), allProjectLinks)

      val twoTrackOldAddressRoadParts = createTwoTrackOldAddressRoadParts(Seq(), transferredPaired, terminated)

      val old_road_two_track_parts = ProjectDeltaCalculator.matchTerminatedRoadwaySections(twoTrackOldAddressRoadParts)

      val twoTrackAdjustedTerminated = old_road_two_track_parts.flatMap(_._1) ++ old_road_two_track_parts.flatMap(_._2)
      val combinedTerminatedTrack = terminated.adjustedSections.filter(_.track == Track.Combined)

      val adjustedTerminated = combinedTerminatedTrack ++ twoTrackAdjustedTerminated

      transferredPaired  should have size 2
      adjustedTerminated should have size 1

      transferredPaired.filter(_._1.startMAddr == 0) should have size 1
      transferredPaired.map(x => {
        (x._1.startMAddr, x._2.startMAddr, x._1.endMAddr, x._2.endMAddr, x._1.discontinuity, x._2.discontinuity)
      }).foreach(_ should (be((0L, 0L, 4403L, 4403L, Continuous, EndOfRoad)) or be((4403L, 762L, 8469L, 4828L, Continuous, Continuous))))

      adjustedTerminated.map(x => {
        (x.startMAddr, x.endMAddr)
      }).foreach(_ should be((0L, 762L)))
    }
  }

  test("Test ProjectDeltaCalculator.partition " +
       "When a project has a new road with two track section in the middle " +
       "Then returns the correct From RoadSection -> To RoadSection mapping.") {
   /*
          /
      T0 /
        / \
         \ \ T1
       T2 \ /
           /T0
          /
   */
    runWithRollback {
      val roadNumber = 46005
      val roadPartNumber = 1
      val createdBy = "Test"
      val roadName = None
      val projectId = Sequences.nextViiteProjectId

      val allProjectLinks = Seq(
        ProjectLink(1000,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,0,107,0,0,None,None,Some(createdBy),1633342,0.0,106.991,TowardsDigitizing,(RoadAddressCP,NoCP),(NoCP,NoCP),List(Point(388414.0,7291967.0,0.0), Point(388498.0,7292033.0,0.0)),projectId,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,106.991,0,0,14,reversed = false,None,1614640323000L,335718924,roadName,None,None,None,None,None,None),
        ProjectLink(1001,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,107,125,0,0,None,None,Some(createdBy),1633252,0.0,17.781,TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,NoCP),List(Point(388498.0,7292033.0,0.0), Point(388512.0,7292044.0,0.0)),projectId,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,17.781,0,0,14,reversed = false,None,1614640323000L,335718924,roadName,None,None,None,None,None,None),
        ProjectLink(1003,roadNumber,roadPartNumber,Track.RightSide,Discontinuity.MinorDiscontinuity,125,241,0,0,None,None,Some(createdBy),1633247,0.0,116.085,TowardsDigitizing,(RoadAddressCP,RoadAddressCP),(NoCP,NoCP),List(Point(388512.0,7292044.0,0.0), Point(388437.0,7292133.0,0.0)),projectId,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,116.085,0,0,14,reversed = false,None,1614640323000L,335718927,roadName,None,None,None,None,None,None),
        ProjectLink(1002,roadNumber,roadPartNumber,Track.LeftSide,Discontinuity.Continuous,125,241,0,0,None,None,Some(createdBy),1633251,0.0,115.928,TowardsDigitizing,(RoadAddressCP,RoadAddressCP),(NoCP,NoCP),List(Point(388498.0,7292033.0,0.0), Point(388424.0,7292123.0,0.0)),projectId,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,115.928,0,0,14,reversed = false,None,1614640323000L,335718928,roadName,None,None,None,None,None,None),
        ProjectLink(1004,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,241,258,0,0,None,None,Some(createdBy),1633250,0.0,17.089,TowardsDigitizing,(RoadAddressCP,NoCP),(NoCP,NoCP),List(Point(388424.0,7292123.0,0.0), Point(388437.0,7292133.0,0.0)),projectId,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,17.089,0,0,14,reversed = false,None,1614640323000L,335718931,roadName,None,None,None,None,None,None),
        ProjectLink(1005,roadNumber,roadPartNumber,Track.Combined,Discontinuity.EndOfRoad,258,367,0,0,None,None,Some(createdBy),1633249,0.0,108.756,TowardsDigitizing,(NoCP,RoadAddressCP),(NoCP,NoCP),List(Point(388437.0,7292133.0,0.0), Point(388522.0,7292202.0,0.0)),projectId,LinkStatus.New,AdministrativeClass.Municipality,FrozenLinkInterface,108.756,0,0,14,reversed = false,None,1614640323000L,335718931,roadName,None,None,None,None,None,None)
      )

      val transferred       = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(allProjectLinks.filter(_.status != LinkStatus.Terminated), allProjectLinks)
      val transferredPaired = transferred.adjustedSections.zip(transferred.originalSections)

      transferredPaired should have size 4
      val createdTargets = transferredPaired.asInstanceOf[List[(RoadwaySection, RoadwaySection)]].sortBy(rs => (rs._1.startMAddr, rs._1.track.value))
      val validTargets = Seq(
        (  0L, 125L, Track.Combined,  Continuous),
        (125L, 241L, Track.RightSide, MinorDiscontinuity),
        (125L, 241L, Track.LeftSide,  Continuous),
        (241L, 367L, Track.Combined,  EndOfRoad))

      createdTargets.map(x => {
        (x._1.startMAddr, x._1.endMAddr, x._1.track,x._1.discontinuity)
      }).zip(validTargets).foreach(p => p._1 should be(p._2))
    }
  }


  // Needs a change? 1. Matching of track addresses should be done during address calculation. 2. Terminations call different overload of partition() that is used in
  // projectdeltacalculator.

//  test("Test ProjectDeltaCalculator.partition When executing a termination and a transfer operation on a single, 2 tracked road part Then returns the correct From RoadSection -> To RoadSection mapping.") {
//    val addresses = (0 to 9).map(i => createRoadAddress(i * 12, 12L)).map(_.copy(track = Track.RightSide))
//    val addresses2 = (0 to 11).map(i => createRoadAddress(i * 10, 10L)).map(l => l.copy(track = Track.LeftSide, id = l.id + 1))
//    val terminations = Seq(toProjectLink(project, LinkStatus.Terminated)(addresses.head), toProjectLink(project, LinkStatus.Terminated)(addresses2.head))
//    val transfers = (addresses.tail ++ addresses2.tail).map(t => {
//      val d = if (t.track == Track.RightSide) 12 else 10
//      (t, toProjectLink(project, LinkStatus.Transfer)(t.copy(startAddrMValue = t.startAddrMValue - d,
//        endAddrMValue = t.endAddrMValue - d)))
//    })
//
//    val termPart = ProjectDeltaCalculator.partition(terminations)
//    termPart should have size 2
//    termPart.foreach(x => {
//      x.startMAddr should be(0L)
//      x.endMAddr should be(11L)
//    })
//
//    val transferParts = ProjectDeltaCalculator.partition(transfers).adjustedSections.map(_._1)
//    transferParts should have size 2
//    transferParts.foreach(x => {
//      val (fr, to) = x
//      fr.startMAddr should be(11L)
//      to.startMAddr should be(0L)
//      fr.endMAddr should be(120L)
//      to.endMAddr should be(109L)
//    })
//  }

  test("Test ProjectDeltaCalculator.partition When executing a unchanged operation and terminating 2 different tracks, the links have different roadwayNumbers Then returns the correct From RoadSection -> To RoadSection mapping, roadways are not considered.") {
    runWithRollback {
      val addresses    = (0 to 9).map(i => {
        createRoadAddress(i * 12, 12L, i)
      }).map(_.copy(track = Track.RightSide, roadwayNumber = 1))
      val addresses2   = (0 to 11).map(i => {
        createRoadAddress(i * 10, 10L, i)
      }).map(l => {
        l.copy(track = Track.LeftSide, id = l.id + 1, roadwayNumber = 2)
      })
      val terminations = Seq(toProjectLink(project, LinkStatus.Terminated)(addresses.last), toProjectLink(project, LinkStatus.Terminated)(addresses2.last))
      val unchanged    = (addresses.init ++ addresses2.init).map(toTransition(project, LinkStatus.UnChanged))

      val termPart2 = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(terminations, Seq()).adjustedSections

      termPart2 should have size 2
      termPart2.foreach(x => {
        x.endMAddr should be(120L)
      })

      val unchangedParts2 = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(unchanged.map(_._2), unchanged.map(_._2))
      val unchangedParts3 = unchangedParts2.adjustedSections.zip(unchangedParts2.originalSections)

      unchangedParts3 should have size 2
      unchangedParts3.foreach(x => {
        val (fr, to) = x
        fr.startMAddr should be(to.startMAddr)
        fr.endMAddr should be(to.endMAddr)
      })
    }
  }


  test("Test ProjectDeltaCalculator.partition When executing an AdministrativeClass change from single AdministrativeClass to two AdministrativeClasses on a single road part with unchanged status and creating a new section at the end" +
       "Then RoadSection -> To RoadSection mapping for Administrative class change and for new part should have correct pairwise (current and new address) sections.") {
    runWithRollback {
      val addresses = (0 to 9).map(i => {
        createRoadAddress(i * 12, 12L)
      })

      val unchanged = addresses.map(a => {
        (a, toProjectLink(project, LinkStatus.UnChanged)(a))
      }).map(ra => {
        if (ra._1.id > 50) ra else (ra._1, ra._2.copy(administrativeClass = AdministrativeClass.Municipality))
      })

      val newLinks  = Seq(ProjectLink(981, 5, 205, Track.Combined, Discontinuity.MinorDiscontinuity, 120, 130, 120, 130, None, None, createdBy = Option(project.createdBy), 981, 0.0, 12.1, TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(0.0, 36.0), Point(0.0, 48.1)), project.id, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 12.1, -1L, -1L, 8, reversed = false, None, 748800L))

      val uncParts = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(unchanged.map(_._2), Seq())
      val roadwaySectionPairs = uncParts.adjustedSections.zip(uncParts.originalSections)

      roadwaySectionPairs should have size 2
      roadwaySectionPairs.foreach(x => {
        val (to, fr) = x
        (fr.startMAddr == 60 || fr.endMAddr == 60) should be(true)
        (to.startMAddr == 60 || to.endMAddr == 60) should be(true)
        if (fr.startMAddr == 0L) fr.administrativeClass should be(AdministrativeClass.Municipality) else fr.administrativeClass should be(AdministrativeClass.State)
        if (to.startMAddr == 0L) to.administrativeClass should be(AdministrativeClass.Municipality) else to.administrativeClass should be(AdministrativeClass.State)
      })

      val newParts = ProjectDeltaCalculator.partition(newLinks, Seq())

      newParts should have size 1
      newParts.foreach(to => {
        to.startMAddr should be(120)
        to.endMAddr should be(130)
      })
    }
  }

  test("Test ProjectDeltaCalculator.partition When executing an AdministrativeClass change to a single AdministrativeClass from two AdministrativeClasses on a single road part with unchanged status and creating a new section at the end" +
       "Then RoadSection -> To RoadSection mapping for Administrative class change and for new part should have correct pairwise (current and new address) sections.") {
    runWithRollback {
      val addresses = (0 to 9).map(i => {
        createRoadAddress(i * 12, 12L)
      }).map(ra => {
        if (ra.id > 50) ra else ra.copy(administrativeClass = AdministrativeClass.Municipality)
      })

      val unchanged = addresses.map(a => {
        (a, toProjectLink(project, LinkStatus.UnChanged)(a))
      })

      val roadway205 = unchanged.flatMap(r => Seq(toRoadway(Seq(r._2))))
      roadwayDAO.create(roadway205)

      val links = unchanged.map(ra => {
                  if (ra._1.id > 50) ra._2 else ra._2.copy(administrativeClass = AdministrativeClass.State)
                })

      val uncParts2 = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(links, Seq())
      val uncParts3 = uncParts2.adjustedSections.zip(uncParts2.originalSections)

      uncParts3 should have size 2
      uncParts3.foreach(x => {
        val (to, fr) = x
        (fr.startMAddr == 60 || fr.endMAddr == 60) should be(true)
        (to.startMAddr == 60 || to.endMAddr == 60) should be(true)
        if (fr.startMAddr == 0L) fr.administrativeClass should be(AdministrativeClass.Municipality) else fr.administrativeClass should be(AdministrativeClass.State)
        if (to.startMAddr == 0L) to.administrativeClass should be(AdministrativeClass.State) else to.administrativeClass should be(AdministrativeClass.State)
      })
    }
  }

  test("Test ProjectDeltaCalculator.partition When end part of a single roadpart is transferred as righside track and a new is created for leftside track" +
                 "Then two track parts should have one section (and not splitted).") {
    val (rwn1, rwn2, rwn3) = (1, 2, 3)
    runWithRollback {
      val addresses = (0 to 14).map(i => {
        createRoadAddress(i * 100, 100)
      })

      val unchanged = addresses.map(a => {
        (a, toProjectLink(project, LinkStatus.UnChanged)(a))
      })

      val roadway205 = Seq(toRoadway(unchanged.map(_._2)).copy(discontinuity = Discontinuity.Discontinuous))
      roadwayDAO.create(roadway205)

      val splitAddress = 800
      var links        = unchanged.map(ra => {
        if (ra._1.id == (splitAddress - 100)) ra._2.copy(roadwayNumber = rwn1, calibrationPointTypes = (NoCP, RoadAddressCP)) else if (ra._1.id < splitAddress) ra._2.copy(roadwayNumber = rwn1) else if (ra._1.id == splitAddress) ra._2.copy(roadwayNumber = rwn2, status = LinkStatus.Transfer, track = Track.RightSide, calibrationPointTypes = (RoadAddressCP, NoCP)) else ra._2.copy(roadwayNumber = rwn2, status = LinkStatus.Transfer, track = Track.RightSide)
      }).map(pl => {
        pl.copy(roadwayId = 0)
      })

      links = links.head.copy(calibrationPointTypes = (RoadAddressCP, NoCP)) +: links.tail.init :+ links.last.copy(discontinuity = Discontinuity.Discontinuous, calibrationPointTypes = (NoCP, RoadAddressCP))

      val id       = links.maxBy(_.id).id + 10
      var newLinks = (0 to 6).flatMap(i => {
        Seq(ProjectLink(id + i, links.head.roadNumber, links.head.roadPartNumber, Track.LeftSide, Discontinuity.Continuous, splitAddress + i * 100, splitAddress + i * 100 + 100, 0, 0, None, None, createdBy = Option(project.createdBy), id + i, 0.0, 100, TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(0.0, id + i), Point(0.0, (id + i) * 100)), links.head.projectId, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 100L, NewIdValue, 0L, links.head.ely, reversed = false, None, 748800L, rwn3))
      })

      newLinks = newLinks.head.copy(calibrationPointTypes = (RoadAddressCP, NoCP)) +: newLinks.init.tail :+ newLinks.last.copy(discontinuity = Discontinuity.Discontinuous, calibrationPointTypes = (NoCP, RoadAddressCP))

      val uncParts2 = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(links ++ newLinks, links ++ newLinks)
      val uncParts3 = uncParts2.adjustedSections.zip(uncParts2.originalSections)

      uncParts3 should have size 3

      val combined = uncParts3.filter(_._1.track == Track.Combined)
      combined should have size 1

      val transfered = uncParts3.filter(_._1.track == Track.RightSide)
      transfered should have size 1

      val newpart = uncParts3.filter(_._1.track == Track.LeftSide)
      newpart should have size 1

      val (toComb, frComb) = combined.head
      (frComb.startMAddr == 0 && frComb.endMAddr == 800) should be(true)
      (toComb.startMAddr == 0 && toComb.endMAddr == 800) should be(true)

      val (toTrans, frTrans) = transfered.head
      (frTrans.startMAddr == 800 && frTrans.endMAddr == 1500) should be(true)
      (toTrans.startMAddr == 800 && toTrans.endMAddr == 1500) should be(true)

      val (toNew, _) = newpart.head
      (toNew.startMAddr == 800 && toNew.endMAddr == 1500) should be(true)
    }
  }

  test("Test ProjectDeltaCalculator.partition When a single roadpart with minor discontinuity and two track part at the end is reversed" +
                "Then two track part track and minor discontinuity should be reversed and address lengths should be unchanged.") {
    runWithRollback {
      val transfer = Seq(
        (createRoadAddress(0, 1).copy(discontinuity = Continuous, id = 0),
          createTransferProjectLink(0,1).copy(
            startAddrMValue         = 1498,
            endAddrMValue           = 1987,
            roadwayId               = 0,
            discontinuity           = EndOfRoad,
            reversed                = true,
            originalStartAddrMValue = 0,
            originalEndAddrMValue   = 489
          )
        ),
        (createRoadAddress(0,1).copy(discontinuity = MinorDiscontinuity, id = 1),
          createTransferProjectLink(0,1).copy(
            startAddrMValue         = 1098,
            endAddrMValue           = 1498,
            roadwayId               = 0,
            discontinuity           = Continuous,
            reversed                = true,
            originalStartAddrMValue = 489,
            originalEndAddrMValue   = 889
          )
        ),
        (createRoadAddress(0,1).copy(discontinuity = Continuous, id = 2),
          createTransferProjectLink(0,1).copy(
            startAddrMValue         = 463,
            endAddrMValue           = 1098,
            roadwayId               = 1,
            discontinuity           = MinorDiscontinuity,
            reversed                = true,
            originalStartAddrMValue = 889,
            originalEndAddrMValue   = 1524
          )
        ),
        (createRoadAddress(0,1).copy(discontinuity = EndOfRoad, id = 3,
          track         = Track.LeftSide
        ),
          createTransferProjectLink(0,1).copy(
            startAddrMValue         = 0,
            endAddrMValue           = 463,
            track                   = Track.LeftSide,
            roadwayId               = 2,
            reversed                = true,
            originalStartAddrMValue = 1524,
            originalEndAddrMValue   = 1987
          )
        ),
        (createRoadAddress(0,1).copy(discontinuity = EndOfRoad, id = 4,
          track         = Track.RightSide
        ),
          createTransferProjectLink(0,1).copy(
            startAddrMValue         = 0,
            endAddrMValue           = 463,
            track                   = Track.RightSide,
            roadwayId               = 3,
            reversed                = true,
            originalStartAddrMValue = 1524,
            originalEndAddrMValue   = 1987
          )
        )
      )

      val createdRoadways205 = transfer.map(pl => toRoadway(Seq(pl._2)))
      val combinedRoadwayPart = createdRoadways205.filter(_.track == Track.Combined)
      val roadways = Seq(
        combinedRoadwayPart
          .find(_.id == 0)
          .get
          .copy(discontinuity   = Discontinuity.MinorDiscontinuity,
            startAddrMValue = 0,
            endAddrMValue   = 889
          ),
        combinedRoadwayPart
          .find(_.id == 1)
          .get
          .copy(discontinuity = Discontinuity.Continuous, startAddrMValue = 889, endAddrMValue = 1524),
        createdRoadways205
          .find(_.track == Track.LeftSide)
          .get
          .copy(track       = Track.RightSide,
            startAddrMValue = 1524,
            endAddrMValue   = 1987,
            discontinuity   = Discontinuity.EndOfRoad
          ),
        createdRoadways205
          .find(_.track == Track.RightSide)
          .get
          .copy(track       = Track.LeftSide,
            startAddrMValue = 1524,
            endAddrMValue   = 1987,
            discontinuity   = Discontinuity.EndOfRoad
          )
      ).map(_.copy(reversed = false))

      roadwayDAO.create(roadways.map(_.copy(reversed = false)))

      val links = transfer.map(_._2).map(_.copy(calibrationPointTypes = (RoadAddressCP,RoadAddressCP), originalCalibrationPointTypes = (RoadAddressCP,RoadAddressCP)))

      val uncParts2 = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(links,links)
      val uncParts3 = uncParts2.adjustedSections.zip(uncParts2.originalSections)

      uncParts3 should have size 4

      val endOfRoadRight   = uncParts3.find(_._2.track == Track.RightSide)
      endOfRoadRight shouldBe defined

      val (p1to, p1fr) = endOfRoadRight.get
      (p1fr.startMAddr == 1524 && p1fr.endMAddr == 1987 && p1fr.track == Track.RightSide) should be(true)
      (p1to.startMAddr == 0 && p1to.endMAddr == 463 && p1to.track == Track.LeftSide) should be(true)
      // Check address lenght has not changed.
      p1fr.endMAddr - p1fr.startMAddr should be(p1to.endMAddr - p1to.startMAddr)

      val endOfRoadLeft   = uncParts3.find(_._2.track == Track.LeftSide)
      endOfRoadLeft shouldBe defined

      val (p2to, p2fr) = endOfRoadLeft.get
      (p2fr.startMAddr == 1524 && p2fr.endMAddr == 1987 && p2fr.track == Track.LeftSide) should be(true)
      (p2to.startMAddr == 0 && p2to.endMAddr == 463 && p2to.track == Track.RightSide) should be(true)
      p2fr.endMAddr - p2fr.startMAddr should be(p2to.endMAddr - p2to.startMAddr)

      val combinedMiddle   = uncParts3.find(_._2.startMAddr == 889)
      combinedMiddle shouldBe defined

      val (p4to, p4fr) = combinedMiddle.get
      (p4fr.startMAddr == 889 && p4fr.endMAddr == 1524 && p4fr.track == Track.Combined) should be(true)
      (p4to.startMAddr == 463 && p4to.endMAddr == 1098 && p4to.track == Track.Combined) should be(true)
      p4fr.endMAddr - p4fr.startMAddr should be(p4to.endMAddr - p4to.startMAddr)

      val combinedStart   = uncParts3.find(_._2.startMAddr == 0)
      combinedStart shouldBe defined

      val (p3to, p3fr) = combinedStart.get
      (p3fr.startMAddr == 0 && p3fr.endMAddr == 889 && p3fr.track == Track.Combined) should be(true)
      (p3to.startMAddr == 1098 && p3to.endMAddr == 1987 && p3to.track == Track.Combined) should be(true)
      p3fr.endMAddr - p3fr.startMAddr should be(p3to.endMAddr - p3to.startMAddr)
    }
  }

  test("Test ProjectDeltaCalculator.partition When transfering two roadparts a small roadpart number to the greater roadpart number" +
                "Then roadway changes should have two rows for each one for each part.") {
    val startRoadPart  = 205
    val endRoadPart    = 206
    val addressMLength = 60

    runWithRollback {
      val addresses = (0 to 9).map(i => {
        createRoadAddress(i * 12, 12L)
      })
      val addressLinks = addresses.filter(_.endAddrMValue <= addressMLength).map(a => {
        (a, toProjectLink(project, LinkStatus.Transfer)(a.copy(roadPartNumber = endRoadPart)).copy(roadwayId = 1))
      }) ++ addresses.filter(_.endAddrMValue > addressMLength).map(a => {
        (a, toProjectLink(project, LinkStatus.Transfer)(a.copy(roadPartNumber = endRoadPart)).copy(roadwayId = 2))
      })
      var links = addressLinks.map(_._2)
      val rw1 = Roadway(1, 1000, 5, startRoadPart, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, addressMLength, ely = 8, startDate = DateTime.now(), createdBy = "", roadName = None)
      val rw2 = Roadway(2, 1001, 5, endRoadPart, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, addressMLength, ely = 8, startDate = DateTime.now(), createdBy = "", roadName = None)

      roadwayDAO.create(Seq(rw1,rw2))

      links = links.filter(_.originalEndAddrMValue <= addressMLength) ++ links.filter(_.originalEndAddrMValue > addressMLength).map(pl => {
        pl.copy(originalStartAddrMValue = pl.originalStartAddrMValue - addressMLength, originalEndAddrMValue = pl.originalEndAddrMValue - addressMLength)
      })

      val partitions  = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(links, links)
      val partitions2 = partitions.adjustedSections.zip(partitions.originalSections)

      partitions2 should have size 2
      val part205 = partitions2.find(_._2.roadPartNumberStart == startRoadPart)
      part205 shouldBe defined
      val part206 = partitions2.find(_._2.roadPartNumberStart == endRoadPart)
      part206 shouldBe defined

      val (to205, fr205) = part205.get
      fr205.startMAddr should be(to205.startMAddr)
      fr205.endMAddr should be(to205.endMAddr)
      fr205.roadPartNumberStart should be(startRoadPart)
      to205.roadPartNumberStart should be(endRoadPart)

      val (to206, fr206) = part206.get
      fr206.startMAddr should be(0)
      fr206.endMAddr should be(addressMLength)
      to206.startMAddr should be(fr205.endMAddr)
      to206.endMAddr should be(fr205.endMAddr + fr206.endMAddr)
      fr206.roadPartNumberStart should be(endRoadPart)
      to206.roadPartNumberStart should be(endRoadPart)
    }
  }

  test("Test ProjectDeltaCalculator.partition When a roadpart with more than one roadway is transfered and reversed" +
                "Then discontinuity should should be unchanged. ") {
    val addressMLengthFirst  = 100
    val addressMLengthSecond = 220
    runWithRollback {
      val addresses = (0 to 9).map(i => {
        createRoadAddress(i * 32, 32)
      })
      val addressLinks = addresses.filter(_.endAddrMValue <= addressMLengthFirst).map(a => {
        (a, toProjectLink(project, LinkStatus.Transfer)(a).copy(roadwayId = 1, reversed = true))
      }) ++ addresses.filter(_.endAddrMValue > addressMLengthFirst).map(a => {
        (a, toProjectLink(project, LinkStatus.Transfer)(a).copy(roadwayId = 2, reversed = true))
      })

      var links = addressLinks.map(_._2)
      val rw1 = Roadway(1, 1000, 5, 205, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, addressMLengthFirst, ely = 8, startDate = DateTime.now(), createdBy = "", roadName = None)
      val rw2 = Roadway(2, 1001, 5, 205, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0, addressMLengthFirst + addressMLengthSecond, ely = 8, startDate = DateTime.now(), createdBy = "", roadName = None)

      roadwayDAO.create(Seq(rw1,rw2))

      links = links.map(pl => {
        pl.copy(startAddrMValue = addressMLengthSecond + addressMLengthFirst - pl.endAddrMValue, endAddrMValue =  addressMLengthSecond + addressMLengthFirst - pl.startAddrMValue)
      })
      links = links.head.copy(discontinuity = Discontinuity.EndOfRoad) +: links.tail

      val partitions  = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(links, links)
      val partitions2 = partitions.adjustedSections.zip(partitions.originalSections)

      partitions2 should have size 1

      val (to, fr) = partitions2.head
      fr.discontinuity should be(to.discontinuity)
      to.discontinuity should be(rw2.discontinuity)
    }
  }

  test("Test ProjectDeltaCalculator.partition When executing a Unchanged operation but changing it's ELY value Then returns the correct From RoadSection -> To RoadSection mapping, ensuring the new ELY is in effect.") {
    runWithRollback {
      val addresses   = (0 to 9).map(i => {
        createRoadAddress(i * 12, 12L)
      })
      val links       = addresses.filter(_.endAddrMValue < 61).map(a => {
        (a, toProjectLink(project, LinkStatus.UnChanged)(a.copy(ely = 5)).copy(roadwayId = 0))
      })
      val roadway205 = toRoadway(links.map(_._2).map(_.copy(ely = 8)))
      roadwayDAO.create(Seq(roadway205))

      val partitions  = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(links.map(_._2), links.map(_._2))
      val partitions2 = partitions.adjustedSections.zip(partitions.originalSections)

      partitions2.size should be(1)
      val (to, fr) = partitions2.head
      fr.startMAddr should be(to.startMAddr)
      fr.endMAddr should be(to.endMAddr)
      fr.ely should be(8)
      to.ely should be(5)
    }
  }

  test("Test ProjectDeltaCalculator.partition When a two track roadpart is transferred to a single track" +
                 "Then RoadwaySections should be formed correctly for Transfer and Terminated parts for either track of termination.") {
    runWithRollback {
      val addresses   = (0 to 9).map(i => {
        createRoadAddress(i * 12, 12L)
      })
      val addressLinks       = addresses.map(a => {
        (a, toProjectLink(project, LinkStatus.Transfer)(a).copy(roadwayId = 0, track = Track.RightSide))
      })
      val rightLinks = addressLinks.map(_._2).map(_.copy(track = Track.Combined))
      val leftLinks  = addressLinks.map(_._2).map(pl => pl.copy(track = Track.LeftSide, status = LinkStatus.Terminated))
      val links = rightLinks ++ leftLinks
      val rightRoadway = toRoadway(rightLinks).copy(track = Track.RightSide, id = 1)
      val leftRoadway  = toRoadway(leftLinks).copy(track = Track.LeftSide, id = 2)
      roadwayDAO.create(Seq(rightRoadway, leftRoadway))

      val partitions  = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(links.filter(_.status != LinkStatus.Terminated), links)
      val partitions2 = partitions.adjustedSections.zip(partitions.originalSections)

      partitions2.size should be(1)
      partitions2.foreach(p => {
        val (to, fr) = p
        fr.startMAddr should be(to.startMAddr)
        fr.endMAddr should be(to.endMAddr)
      })

      val terminatedPartitions = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(links.filter(_.status == LinkStatus.Terminated), links)
      val terminatedPartitions2 = terminatedPartitions.adjustedSections.zip(partitions.originalSections)

      terminatedPartitions2.size should be(1)
    }

    runWithRollback {
      val addresses   = (0 to 9).map(i => {
        createRoadAddress(i * 12, 12L)
      })
      val addressLinks       = addresses.map(a => {
        (a, toProjectLink(project, LinkStatus.Transfer)(a).copy(roadwayId = 0, track = Track.LeftSide))
      })
      val rightLinks = addressLinks.map(_._2).map(_.copy(track = Track.Combined))
      val leftLinks  = addressLinks.map(_._2).map(pl => pl.copy(track = Track.RightSide, status = LinkStatus.Terminated))
      val links = rightLinks ++ leftLinks
      val leftRoadway = toRoadway(rightLinks).copy(track = Track.LeftSide, id = 1)
      val rightRoadway = toRoadway(leftLinks).copy(track = Track.RightSide, id = 2)
      roadwayDAO.create(Seq(rightRoadway, leftRoadway))

      val partitions  = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(links.filter(_.status != LinkStatus.Terminated), links)
      val partitions2 = partitions.adjustedSections.zip(partitions.originalSections)

      partitions2.size should be(1)
      partitions2.foreach(p => {
        val (to, fr) = p
        fr.startMAddr should be(to.startMAddr)
        fr.endMAddr should be(to.endMAddr)
      })

      val terminatedPartitions = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(links.filter(_.status == LinkStatus.Terminated), links)
      val terminatedPartitions2 = terminatedPartitions.adjustedSections.zip(partitions.originalSections)

      terminatedPartitions2.size should be(1)
    }
  }

  test("Test ProjectDeltaCalculator.partition " +
       "When transferring a part of an existing roadpart to as another roadpart with more than one roadway" +
       "Then continuity codes should be correct.") {
    runWithRollback {
      val originalDiscontinuity = Discontinuity.EndOfRoad
      val newDiscontinuity      = Discontinuity.Discontinuous

      /* A road of length 120 is split from 60 to two parts and discontinuity is changed. */
      val addresses  = (0 to 9).map(i => {
       createRoadAddress(i * 12, 12L)
      })

      val splitAddress       = 60
      val linksForRoad205    = addresses.filter(_.endAddrMValue <= splitAddress).map(a => {
        toProjectLink(project, LinkStatus.UnChanged)(a).copy(roadwayId = 0)
      })
      val linksForRoad206rw1 = addresses.filter(a => {
        a.endAddrMValue > splitAddress && a.endAddrMValue < 90
      }).map(a => {
        toProjectLink(project, LinkStatus.Transfer)(a).copy(roadwayId = 1, roadPartNumber = 206)
      })
      val linksForRoad206rw2 = addresses.filter(a => {
        a.endAddrMValue > 90
      }).map(a => {
        toProjectLink(project, LinkStatus.Transfer)(a).copy(roadwayId = 2, roadPartNumber = 206)
      })

      /* The first part has one roadway, the seconds has two roadways. */
      val roadway205_1 = toRoadway(linksForRoad205)
      val roadway205_2 = toRoadway(linksForRoad206rw1).copy(id = 1)

      val roadway205_3 = toRoadway(linksForRoad206rw2).copy(id = 2, discontinuity = originalDiscontinuity)
      roadwayDAO.create(Seq(roadway205_1, roadway205_2, roadway205_3))

      val addressedRoad206Links      = (linksForRoad206rw1 ++ linksForRoad206rw2).map(pl => {
        pl.copy(startAddrMValue = pl.startAddrMValue - splitAddress, endAddrMValue = pl.endAddrMValue - splitAddress)
      })

      val road206Links      = addressedRoad206Links.init :+ addressedRoad206Links.last.copy(discontinuity = newDiscontinuity)
      val partitions  = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(linksForRoad205 ++ road206Links, linksForRoad205)
      val pairedPartitions = partitions.adjustedSections.zip(partitions.originalSections)

      pairedPartitions.size should be(2)
      val road205and206Partitions = pairedPartitions.partition(par => {
        par._1.roadPartNumberStart == 205 && par._2.roadPartNumberStart == 205
      })
      road205and206Partitions._1 should have size 1
      road205and206Partitions._2 should have size 1

      /* Check continuities and road addresses. */
      val (to1, fr1) = road205and206Partitions._1.head
      fr1.startMAddr should be(0)
      fr1.endMAddr should be(splitAddress)
      fr1.discontinuity should be(Discontinuity.Continuous)
      to1.startMAddr should be(0)
      to1.endMAddr should be(splitAddress)
      to1.discontinuity should be(Discontinuity.Continuous)

      val (to2, fr2) = road205and206Partitions._2.head
      fr2.startMAddr should be(splitAddress)
      fr2.endMAddr should be(120)
      fr2.discontinuity should be(originalDiscontinuity)
      to2.startMAddr should be(0)
      to2.endMAddr should be(splitAddress)
      to2.discontinuity should be(newDiscontinuity)
    }
  }

  test("Test ProjectDeltaCalculator.partition When executing a Unchanged operation but changing it's Discontinuity value Then returns the correct From RoadSection -> To RoadSection mapping, ensuring the new Discontinuity is in effect.") {
    runWithRollback {
      val addresses   = (0 to 9).map(i => {
        createRoadAddress(i * 12, 12L)
      })
      val links       = addresses.map(a => {
        if (a.endAddrMValue == 60) {
          (a, toProjectLink(project, LinkStatus.UnChanged)(a.copy(discontinuity = Discontinuity.MinorDiscontinuity)).copy(roadwayId = 0))
        } else {
          toTransition(project, LinkStatus.UnChanged)(a)
        }
      })

      val roadway205 = toRoadway(links.map(_._2))
      roadwayDAO.create(Seq(roadway205))

      val partitions  = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(links.map(_._2), links.map(_._2))
      val partitions2 = partitions.adjustedSections.zip(partitions.originalSections)

      partitions2.size should be(2)
      partitions2.foreach(x => {
        val (to, fr) = x
        if (fr.startMAddr == 0) {
          fr.discontinuity should be(Discontinuity.Continuous)
          to.discontinuity should be(Discontinuity.MinorDiscontinuity)
        } else {
          fr.discontinuity should be(Discontinuity.Continuous)
          to.discontinuity should be(Discontinuity.Continuous)
        }
      })
    }
  }

  test("Test ProjectDeltaCalculator.partition When executing Multiple transfers with reversal and discontinuity change operations Then returns the correct From RoadSection -> To RoadSection mapping.") {
    val transfer = Seq((createRoadAddress(0, 502).copy(discontinuity = MinorDiscontinuity),
      createTransferProjectLink(1524, 502).copy(reversed = true)),
      (createRoadAddress(502, 1524),
        createTransferProjectLink(0, 1524).copy(discontinuity = MinorDiscontinuity, reversed = true)))
    val mapping =
      ProjectDeltaCalculator.partition(transfer).adjustedSections.map(_._1)
    mapping should have size 2
    mapping.foreach { case (from, to) =>
      from.endMAddr - from.startMAddr should be(to.endMAddr - to.startMAddr)
      if (from.discontinuity != Continuous)
        to.discontinuity should be(Continuous)
      else
        to.discontinuity should be(MinorDiscontinuity)
    }
  }

  test("Multiple transfers with reversal and discontinuity") {
    val transfer = Seq((createRoadAddress(0, 502).copy(discontinuity = MinorDiscontinuity),
      createTransferProjectLink(1524, 502).copy(reversed = true)),
      (createRoadAddress(502, 1524),
        createTransferProjectLink(0, 1524).copy(discontinuity = MinorDiscontinuity, reversed = true)))
    val mapping =
      ProjectDeltaCalculator.partition(transfer).adjustedSections.map(_._1)
    mapping should have size 2
    mapping.foreach { case (from, to) =>
      from.endMAddr - from.startMAddr should be(to.endMAddr - to.startMAddr)
      if (from.discontinuity != Continuous)
        to.discontinuity should be(Continuous)
      else
        to.discontinuity should be(MinorDiscontinuity)
    }
  }

  test("Partitioner should separate links containing calibration points whose origin is ProjectLink") {
    runWithRollback {
      val addresses          = (0 to 9).map(i => {
        createRoadAddress(i * 2, 2L)
      })
      val projectLinksWithCp = addresses.sortBy(_.startAddrMValue).map(a => {
        val projectLink = toProjectLink(project, LinkStatus.UnChanged)(a.copy(ely = 5))
        if (a.id == 10L) (a.copy(roadwayNumber = 1), projectLink.copy(calibrationPointTypes = (CalibrationPointDAO.CalibrationPointType.NoCP, CalibrationPointDAO.CalibrationPointType.UserDefinedCP), roadwayNumber = 1)) else if (a.id > 10L) (a.copy(roadwayNumber = 2), projectLink.copy(roadwayNumber = 2)) else (a.copy(roadwayNumber = 1), projectLink.copy(roadwayNumber = 1))
      })

      val partitionCp = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(projectLinksWithCp.map(_._2), Seq()).adjustedSections
      partitionCp.size should be(2)
      val firstSection  = partitionCp.head
      val secondSection = partitionCp.last
      val cutPoint      = projectLinksWithCp.find(_._2.roadwayId == 10L).get._2

      firstSection.startMAddr should be(projectLinksWithCp.head._2.startAddrMValue)
      firstSection.endMAddr should be(cutPoint.endAddrMValue)
      secondSection.startMAddr should be(cutPoint.endAddrMValue)
      secondSection.endMAddr should be(projectLinksWithCp.last._2.endAddrMValue)
    }
  }

  test("Partitioner should not separate links containing junction calibration points whose origin is ProjectLink") {
    runWithRollback {
      val addresses = (0 to 9).map(i => {
        createRoadAddress(i * 2, 2L)
      })
      val projectLinksWithCp = addresses.sortBy(_.startAddrMValue).map(a => {
        val projectLink = toProjectLink(project, LinkStatus.UnChanged)(a.copy(ely = 5))
        if (a.id == 10L)
          (a.copy(roadwayNumber = 1), projectLink.copy(calibrationPointTypes = (CalibrationPointDAO.CalibrationPointType.NoCP, CalibrationPointDAO.CalibrationPointType.JunctionPointCP), roadwayNumber = 1))
        else if (a.id > 10L)
            (a.copy(roadwayNumber = 2), projectLink.copy(roadwayNumber = 2))
        else
            (a.copy(roadwayNumber = 1), projectLink.copy(roadwayNumber = 1))
      })

      val partitionCp = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(projectLinksWithCp.map(_._2), Seq()).adjustedSections
      partitionCp.size should be(1)
      val firstSection  = partitionCp.head

      firstSection.startMAddr should be(projectLinksWithCp.head._2.startAddrMValue)
      firstSection.endMAddr should be(projectLinksWithCp.last._2.endAddrMValue)
    }
  }

  test("Test partitioner When a roadpart is combined to another with a new link having other part reversed" +
                 "Then Changetable should have a tranfer row reversed and a new row not reversed " +
                 "and AET and LET values correctly.") {
    runWithRollback {
      val addresses = (0 to 5).map(i => {
        createRoadAddress(i * 2, 2L)
      })

      val transferLinks = addresses.take(5).sortBy(_.startAddrMValue).map(a => {
        val projectLink = toProjectLink(project, LinkStatus.Transfer)(a)
        projectLink.copy(reversed = true, roadwayId = 2)
      })

      val maxAddr       = transferLinks.last.endAddrMValue
      val lengthChange  = 2
      val reversedTrans = transferLinks.map(pl => {
        pl.copy(startAddrMValue = maxAddr - pl.endAddrMValue, endAddrMValue = maxAddr - pl.startAddrMValue)
      })

      val transferLinks206 = addresses.sortBy(_.startAddrMValue).map(a => {
        val projectLink = toProjectLink(project, LinkStatus.Transfer)(a)
        projectLink.copy(reversed = false, roadwayId = 3, startAddrMValue = maxAddr + lengthChange + a.startAddrMValue, endAddrMValue = maxAddr + lengthChange + a.endAddrMValue)
      })

      val projectLink = toProjectLink(project, LinkStatus.New)(addresses.head)
      val newLink     = projectLink.copy(reversed = false, startAddrMValue = maxAddr, endAddrMValue = maxAddr + lengthChange)

      val roadway205 = toRoadway(Seq(transferLinks.head.copy(startAddrMValue = 0, endAddrMValue = addresses.take(5).last.endAddrMValue))).copy(id = 2)
      val roadway206 = toRoadway(Seq(transferLinks.head.copy(startAddrMValue = 0, endAddrMValue = addresses.last.endAddrMValue, roadPartNumber = 206))).copy(id = 3)
      roadwayDAO.create(Seq(roadway205, roadway206))

      val partitioned      = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks((reversedTrans :+ newLink )++ transferLinks206, Seq())
      val adjustedSections = partitioned.adjustedSections
      adjustedSections.size should be(3)

      val (firstSection, secondSection) = adjustedSections.partition(_.roadwayNumber == -1000)
      firstSection  should have(size(1))
      secondSection should have(size(2))

      firstSection.head.startMAddr  should be(maxAddr)
      firstSection.head.endMAddr    should be(maxAddr + lengthChange)
      firstSection.head.reversed    should be(false)
      secondSection.head.startMAddr should be(maxAddr + lengthChange)
      secondSection.head.endMAddr   should be(maxAddr + lengthChange + roadway206.endAddrMValue)
      secondSection.head.reversed   should be(false)
      secondSection.last.startMAddr should be(0)
      secondSection.last.endMAddr   should be(maxAddr)
      secondSection.last.reversed   should be(true)

      val originalSections205 = partitioned.originalSections.find(p => p.roadPartNumberStart == 205 && p.roadwayNumber == 0).get
      originalSections205.startMAddr should be(0)
      originalSections205.endMAddr   should be(roadway205.endAddrMValue)

      val originalSections206 = partitioned.originalSections.find(_.roadPartNumberStart == 206).get
      originalSections206.startMAddr should be(0)
      originalSections206.endMAddr   should be(roadway206.endAddrMValue)
    }
  }
}
