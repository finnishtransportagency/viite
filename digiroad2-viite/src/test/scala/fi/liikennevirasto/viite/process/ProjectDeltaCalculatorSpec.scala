package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.GeometryUtils
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.NormalLinkInterface
import fi.liikennevirasto.digiroad2.asset.SideCode.TowardsDigitizing
import fi.liikennevirasto.digiroad2.asset.{AdministrativeClass, LinkGeomSource}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.{LeftSide, RightSide}
import fi.liikennevirasto.viite.NewIdValue
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.{NoCP, RoadAddressCP}
import fi.liikennevirasto.viite.dao.Discontinuity.{Continuous, EndOfRoad, MinorDiscontinuity}
import fi.liikennevirasto.viite.dao.LinkStatus.{Terminated, Transfer}
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.util.{toProjectLink, toTransition}
import org.joda.time.DateTime
import org.scalatest.enablers.Definition.definitionOfOption
import org.scalatest.{FunSuite, Matchers}
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

      val partitions = ProjectDeltaCalculator.partitionWithProjectLinks(projectLinks.map(_._2), projectLinks.map(_._2))
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

      val partitions = ProjectDeltaCalculator.partitionWithProjectLinks(projectLinks, projectLinks)
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

      val partitions = ProjectDeltaCalculator.partitionWithProjectLinks(projectLinks, projectLinks)
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
      val unchangedParts2 = ProjectDeltaCalculator.partitionWithProjectLinks(numberingLinks.map(_._2), numberingLinks.map(_._2))
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

      val transferred = ProjectDeltaCalculator.partitionWithProjectLinks(allProjectLinks.filter(_.status != LinkStatus.Terminated), allProjectLinks)
      val transferredPaired = transferred.adjustedSections.zip(transferred.originalSections)

      val terminated = ProjectDeltaCalculator.partitionWithProjectLinks(allProjectLinks.filter(_.status == LinkStatus.Terminated), allProjectLinks)

      val twoTrackOldAddressRoadParts = (transferredPaired.map(roadwaySection => {
        (roadwaySection._2, "other")
      }).toSeq ++ terminated.adjustedSections.map(roadwaySection => {
        (roadwaySection, "terminated")
      }).toSeq).filterNot(_._1.track == Track.Combined).sortBy(_._1.startMAddr).groupBy(p => {
        (p._1.roadNumber, p._1.roadPartNumberStart)
      }).map(p => {
        p._1 -> p._2.groupBy(_._1.track).values
      })

      val old_road_two_track_parts = ProjectDeltaCalculator.calc_parts(twoTrackOldAddressRoadParts)

      val twoTrackAdjustedTerminated = old_road_two_track_parts.flatMap(_._1) ++ old_road_two_track_parts.flatMap(_._2)
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

      val termPart2 = ProjectDeltaCalculator.partitionWithProjectLinks(terminations, Seq()).adjustedSections

      termPart2 should have size 2
      termPart2.foreach(x => {
        x.endMAddr should be(120L)
      })

      val unchangedParts2 = ProjectDeltaCalculator.partitionWithProjectLinks(unchanged.map(_._2), unchanged.map(_._2))
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

      val uncParts = ProjectDeltaCalculator.partitionWithProjectLinks(unchanged.map(_._2), Seq())
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

      val uncParts2 = ProjectDeltaCalculator.partitionWithProjectLinks(links, Seq())
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

      val uncParts2 = ProjectDeltaCalculator.partitionWithProjectLinks(links ++ newLinks, links ++ newLinks)
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

      val uncParts2 = ProjectDeltaCalculator.partitionWithProjectLinks(links,links)
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

      val partitions  = ProjectDeltaCalculator.partitionWithProjectLinks(links, links)
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

      val partitions  = ProjectDeltaCalculator.partitionWithProjectLinks(links, links)
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

      val partitions  = ProjectDeltaCalculator.partitionWithProjectLinks(links.map(_._2), links.map(_._2))
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

      val partitions  = ProjectDeltaCalculator.partitionWithProjectLinks(links.filter(_.status != LinkStatus.Terminated), links)
      val partitions2 = partitions.adjustedSections.zip(partitions.originalSections)

      partitions2.size should be(1)
      partitions2.foreach(p => {
        val (to, fr) = p
        fr.startMAddr should be(to.startMAddr)
        fr.endMAddr should be(to.endMAddr)
      })

      val terminatedPartitions = ProjectDeltaCalculator.partitionWithProjectLinks(links.filter(_.status == LinkStatus.Terminated), links)
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

      val partitions  = ProjectDeltaCalculator.partitionWithProjectLinks(links.filter(_.status != LinkStatus.Terminated), links)
      val partitions2 = partitions.adjustedSections.zip(partitions.originalSections)

      partitions2.size should be(1)
      partitions2.foreach(p => {
        val (to, fr) = p
        fr.startMAddr should be(to.startMAddr)
        fr.endMAddr should be(to.endMAddr)
      })

      val terminatedPartitions = ProjectDeltaCalculator.partitionWithProjectLinks(links.filter(_.status == LinkStatus.Terminated), links)
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
      val partitions  = ProjectDeltaCalculator.partitionWithProjectLinks(linksForRoad205 ++ road206Links, linksForRoad205)
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

      val partitions  = ProjectDeltaCalculator.partitionWithProjectLinks(links.map(_._2), links.map(_._2))
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

      val partitionCp = ProjectDeltaCalculator.partitionWithProjectLinks(projectLinksWithCp.map(_._2), Seq()).adjustedSections
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

      val partitionCp = ProjectDeltaCalculator.partitionWithProjectLinks(projectLinksWithCp.map(_._2), Seq()).adjustedSections
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

      val partitioned      = ProjectDeltaCalculator.partitionWithProjectLinks((reversedTrans :+ newLink )++ transferLinks206, Seq())
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
