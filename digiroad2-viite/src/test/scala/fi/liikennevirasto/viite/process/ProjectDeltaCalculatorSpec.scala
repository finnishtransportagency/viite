package fi.liikennevirasto.viite.process

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.NormalLinkInterface
import fi.liikennevirasto.digiroad2.asset.SideCode.TowardsDigitizing
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.AdministrativeClass
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.{NoCP, RoadAddressCP}
import fi.liikennevirasto.viite.dao.Discontinuity.{Continuous, MinorDiscontinuity}
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.dao._
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import fi.liikennevirasto.viite.util.toProjectLink
import fi.liikennevirasto.viite.util.toTransition

class ProjectDeltaCalculatorSpec extends FunSuite with Matchers {
  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def runWithRollback[T](f: => T): T = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
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
    ProjectLink(roadAddress.id, roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track, roadAddress.discontinuity, roadAddress.startAddrMValue + project.id, roadAddress.endAddrMValue + project.id, roadAddress.startAddrMValue + project.id, roadAddress.endAddrMValue + project.id, roadAddress.startDate, roadAddress.endDate, createdBy = Option(project.createdBy), roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue, roadAddress.sideCode, roadAddress.calibrationPointTypes, (roadAddress.startCalibrationPointType, roadAddress.endCalibrationPointType), roadAddress.geometry, project.id, status, roadAddress.administrativeClass, roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry), roadAddress.id, roadAddress.linearLocationId, roadAddress.ely, reversed = false, None, 748800L)
  }

  test("Test ProjectDeltaCalculator.partition When executing multiple transfers on single road part Then returns the correct From RoadSection -> To RoadSection mapping.") {
    val transfer1 = (0 to 10).map(x => (createRoadAddress(x * 10, 10), createTransferProjectLink(x * 10, 10)))
    val transfer2 = (12 to 15).map(x => (createRoadAddress(x * 10, 10), createTransferProjectLink(x * 10, 10)))
    val mapping =
      ProjectDeltaCalculator.partition(transfer1 ++ transfer2).adjustedSections.keys
    mapping.foreach { elem =>
      elem._1.startMAddr should be(elem._2.startMAddr - project.id)
      elem._1.endMAddr should be(elem._2.endMAddr - project.id)
      elem._1.track should be(elem._2.track)
    }
  }

  test("Test ProjectDeltaCalculator.partition When executing a Unchanged and 2 transfer on single road part Then returns the correct From RoadSection -> To RoadSection mapping.") {
    val addresses = (0 to 10).map(i => createRoadAddress(i * 10, 10L))
    val addresses2 = (11 to 21).map(i => createRoadAddress(i * 10, 10L)).map(a => a.copy(roadPartNumber = 206, startAddrMValue = a.startAddrMValue - 110L, endAddrMValue = a.endAddrMValue - 110L))
    val (transferLinks1, transferLinks2) = addresses2.map(toTransition(project, LinkStatus.Transfer)).partition(_._2.startAddrMValue == 0L)
    val projectLinks = addresses.map(toTransition(project, LinkStatus.UnChanged)) ++
      transferLinks1.map(l => (l._1, l._2.copy(roadPartNumber = 205, startAddrMValue = 110L, endAddrMValue = 120L))) ++
      transferLinks2.map(l => (l._1, l._2.copy(startAddrMValue = l._2.startAddrMValue - 10L, endAddrMValue = l._2.endAddrMValue - 10L)))

    val partitions = ProjectDeltaCalculator.partition(projectLinks, oppositeSections = Seq()).adjustedSections.keys
    partitions should have size 3
    val start205 = partitions.find(p => p._1.roadPartNumberStart == 205 && p._2.roadPartNumberStart == 205)
    val to205 = partitions.find(p => p._1.roadPartNumberStart == 206 && p._2.roadPartNumberStart == 205)
    val remain205 = partitions.find(p => p._1.roadPartNumberStart == 206 && p._2.roadPartNumberStart == 206)

    start205.size should be (1)
    to205.size should be (1)
    remain205.size should be (1)

    start205.map(x => (x._1.startMAddr, x._2.startMAddr, x._1.endMAddr, x._2.endMAddr)) should be(Some((0L, 0L, 110L, 110L)))
  }

  test("Test ProjectDeltaCalculator.partition When executing a numbering operation on single road part Then returns the correct From RoadSection -> To RoadSection mapping with the new road number/road part number.") {
    val addresses = (0 to 10).map(i => createRoadAddress(i * 10, 10L))
    val numberingLinks = addresses.map(a => (a, a.copy(roadNumber = 12345, roadPartNumber = 1))).map(x => (x._1, toProjectLink(project, LinkStatus.Numbering)(x._2)))

    val partitions = ProjectDeltaCalculator.partition(numberingLinks, oppositeSections = Seq()).adjustedSections.keys
    partitions should have size 1
    val correctRoadNumber = partitions.find(p => p._1.roadNumber == 5 && p._2.roadNumber == 12345)
    val correctRoadPartNumber = partitions.find(p => p._1.roadPartNumberStart == 205 && p._1.roadPartNumberEnd == 205 && p._2.roadPartNumberStart == 1 && p._2.roadPartNumberEnd == 1)
    correctRoadNumber.size should be(1)
    correctRoadPartNumber.size should be(1)

    correctRoadNumber.get._1.track should be(correctRoadNumber.get._2.track)
    correctRoadNumber.get._1.discontinuity should be(correctRoadNumber.get._2.discontinuity)
    correctRoadNumber.map(x => (x._1.startMAddr, x._2.startMAddr, x._1.endMAddr, x._2.endMAddr)) should be(Some((0L, 0L, 110L, 110L)))
  }

  test("Test ProjectDeltaCalculator.partition When executing a termination and a transfer operation on a single, 2 tracked road part Then returns the correct From RoadSection -> To RoadSection mapping.") {
    val addresses = (0 to 9).map(i => createRoadAddress(i * 12, 12L)).map(_.copy(track = Track.RightSide))
    val addresses2 = (0 to 11).map(i => createRoadAddress(i * 10, 10L)).map(l => l.copy(id = l.id + 1, track = Track.LeftSide))
    val terminations = Seq(toProjectLink(project, LinkStatus.Terminated)(addresses.head), toProjectLink(project, LinkStatus.Terminated)(addresses2.head))
    val transfers = (addresses.tail ++ addresses2.tail).map(t => {
      val d = if (t.track == Track.RightSide) 12 else 10
      (t, toProjectLink(project, LinkStatus.Transfer)(t.copy(startAddrMValue = t.startAddrMValue - d, endAddrMValue = t.endAddrMValue - d)))
    })

    val termPart = ProjectDeltaCalculator.partition(terminations)
    termPart should have size 2
    termPart.foreach(x => {
      x.startMAddr should be(0L)
      x.endMAddr should be(11L)
    })

    val transferParts = ProjectDeltaCalculator.partition(transfers, oppositeSections = Seq()).adjustedSections.keys
    transferParts should have size 2
    transferParts.foreach(x => {
      val (fr, to) = x
      fr.startMAddr should be(11L)
      to.startMAddr should be(0L)
      fr.endMAddr should be(120L)
      to.endMAddr should be(109L)
    })
  }

  test("Test ProjectDeltaCalculator.partition When executing a unchanged operation and terminating 2 different tracks, the links have different roadwayNumbers Then returns the correct From RoadSection -> To RoadSection mapping, roadways are not considered.") {
    val addresses = (0 to 9).map(i => createRoadAddress(i * 12, 12L, i)).map(_.copy(track = Track.RightSide, roadwayNumber = 1))
    val addresses2 = (0 to 11).map(i => createRoadAddress(i * 10, 10L, i)).map(l => l.copy(id = l.id + 1, track = Track.LeftSide, roadwayNumber = 2))
    val terminations = Seq(toProjectLink(project, LinkStatus.Terminated)(addresses.last), toProjectLink(project, LinkStatus.Terminated)(addresses2.last))
    val unchanged = (addresses.init ++ addresses2.init).map(toTransition(project, LinkStatus.UnChanged))

    val termPart = ProjectDeltaCalculator.partition(terminations)
    termPart should have size 2
    termPart.foreach(x => {
      x.endMAddr should be(120L)
    })

    val unchangedParts = ProjectDeltaCalculator.partition(unchanged, oppositeSections = Seq()).adjustedSections
    unchangedParts.keys should have size 2
    unchangedParts.keys.foreach(x => {
      val (fr, to) = x
      fr.startMAddr should be(to.startMAddr)
      fr.endMAddr should be(to.endMAddr)
    })
  }

  test("Test ProjectDeltaCalculator.partition When executing a unchanged, a 2 track termination and a transfer operation on a single road part with 2 tracks Then returns the correct From RoadSection -> To RoadSection mapping.") {
    val addresses = (0 to 9).map(i => createRoadAddress(i * 12, 12L)).map(_.copy(track = Track.RightSide))
    val addresses2 = (0 to 11).map(i => createRoadAddress(i * 10, 10L)).map(l => l.copy(id = l.id + 1, track = Track.LeftSide))
    val terminations = Seq(addresses(4), addresses(5), addresses2(5), addresses2(6)).map(t => {
      toProjectLink(project, LinkStatus.Terminated)(t)
    })
    val unchanged = (addresses.take(3) ++ addresses2.take(4)).map(toTransition(project, LinkStatus.UnChanged))
    val transfers = (addresses.drop(5) ++ addresses2.drop(6)).map(toTransition(project, LinkStatus.Transfer)).map {
      case (a, pl) =>
        val d = if (pl.track == Track.RightSide) 11L else 7L
        (a, pl.copy(startAddrMValue = pl.startAddrMValue - d, endAddrMValue = pl.endAddrMValue - d))
    }

    val newLinks = Seq(ProjectLink(981, 5, 205, Track.RightSide, Discontinuity.MinorDiscontinuity, 36, 49, 36, 49, None, None, createdBy = Option(project.createdBy), 981, 0.0, 12.1, TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(0.0, 36.0), Point(0.0, 48.1)), project.id, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 12.1, -1L, -1L, 8, reversed = false, None, 85900L),
      ProjectLink(982, 5, 205, Track.LeftSide, Discontinuity.MinorDiscontinuity, 40, 53, 40, 53, None, None, createdBy = Option(project.createdBy), 982, 0.0, 12.2, TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(0.0, 36.0), Point(0.0, 48.2)), project.id, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 12.2, -1L, -1L, 8, reversed = false, None, 85900L),
      ProjectLink(983, 5, 205, Track.RightSide, Discontinuity.MinorDiscontinuity, 109, 124, 109, 124, None, None, createdBy = Option(project.createdBy), 983, 0.0, 15.2, TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(0.0, 120.0), Point(0.0, 135.2)), project.id, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 15.2, -1L, -1L, 8, reversed = false, None, 85900L),
      ProjectLink(984, 5, 205, Track.LeftSide, Discontinuity.MinorDiscontinuity, 113, 127, 113, 127, None, None, createdBy = Option(project.createdBy), 984, 0.0, 14.2, TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(0.0, 120.0), Point(0.0, 135.2)), project.id, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 14.2, -1L, -1L, 8, reversed = false, None, 85900L)
    )

    val termPart: Seq[RoadwaySection] = ProjectDeltaCalculator.partition(terminations)
    termPart should have size 2
    termPart.foreach(x => {
      x.endMAddr should be(71L)
    })


    val uncParts = ProjectDeltaCalculator.partition(unchanged, oppositeSections = Seq()).adjustedSections.keys
    uncParts should have size 2
    uncParts.foreach(x => {
      val (fr, _) = x
      fr.startMAddr should be(0L)
      fr.endMAddr should be(38L)
    })

    val transferParts = ProjectDeltaCalculator.partition(transfers, oppositeSections = Seq()).adjustedSections.keys
    transferParts should have size 2
    transferParts.foreach(x => {
      val (fr, to) = x
      fr.endMAddr should be(120L)
      to.endMAddr should be(111L)
    })

    val newParts = ProjectDeltaCalculator.partition(newLinks)
    newParts should have size 4
    newParts.filter(_.startMAddr < 100).foreach(to => {
      to.endMAddr should be(51)
    })
    newParts.filter(_.startMAddr >= 100).foreach(to => {
      to.endMAddr should be(125)
    })
  }

  test("Test ProjectDeltaCalculator.partition When executing a unchanged and a road type change operation on a single road part, then create a new road Then returns the correct From RoadSection -> To RoadSection mapping.") {
    val addresses = (0 to 9).map(i => createRoadAddress(i * 12, 12L)).map(ra =>
      if (ra.id > 50)
        ra
      else
        ra.copy(administrativeClass = AdministrativeClass.Municipality)
    )
    val unchanged = addresses.map(a => (a, toProjectLink(project, LinkStatus.UnChanged)(a)))

    val newLinks = Seq(ProjectLink(981, 5, 205, Track.Combined, Discontinuity.MinorDiscontinuity, 120, 130, 120, 130, None, None, createdBy = Option(project.createdBy), 981, 0.0, 12.1, TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(0.0, 36.0), Point(0.0, 48.1)), project.id, LinkStatus.New, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, 12.1, -1L, -1L, 8, reversed = false, None, 748800L))
    val uncParts = ProjectDeltaCalculator.partition(unchanged, Seq()).adjustedSections.keys
    uncParts should have size 2
    uncParts.foreach(x => {
      val (fr, to) = x
      (fr.startMAddr == 60 || fr.endMAddr == 60) should be(true)
      (to.startMAddr == 60 || to.endMAddr == 60) should be(true)
      if (fr.startMAddr == 0L)
        fr.administrativeClass should be(AdministrativeClass.Municipality)
      else
        fr.administrativeClass should be(AdministrativeClass.State)
      if (to.startMAddr == 0L)
        to.administrativeClass should be(AdministrativeClass.Municipality)
      else
        to.administrativeClass should be(AdministrativeClass.State)
    })
    val newParts = ProjectDeltaCalculator.partition(newLinks)
    newParts should have size 1
    newParts.foreach(to => {
      to.startMAddr should be(120)
      to.endMAddr should be(130)
    })
  }

  test("Test ProjectDeltaCalculator.partition When executing a Unchanged operation but changing it's ELY value Then returns the correct From RoadSection -> To RoadSection mapping, ensuring the new ELY is in effect.") {
    val addresses = (0 to 9).map(i => createRoadAddress(i * 12, 12L))
    val links = addresses.filter(_.endAddrMValue < 61).map(a => (a, toProjectLink(project, LinkStatus.UnChanged)(a.copy(ely = 5))))
    val partitioned = ProjectDeltaCalculator.partition(links, Seq()).adjustedSections.keys
    partitioned.size should be(1)
    val (fr, to) = partitioned.head
    fr.startMAddr should be(to.startMAddr)
    fr.endMAddr should be(to.endMAddr)
    fr.ely should be(8)
    to.ely should be(5)
  }

  test("Test ProjectDeltaCalculator.partition When executing a Unchanged operation but changing it's Discontinuity value Then returns the correct From RoadSection -> To RoadSection mapping, ensuring the new Discontinuity is in effect.") {
    val addresses = (0 to 9).map(i => createRoadAddress(i * 12, 12L))
    val links = addresses.map(a => {
      if (a.endAddrMValue == 60) {
        (a, toProjectLink(project, LinkStatus.UnChanged)(a.copy(discontinuity = Discontinuity.MinorDiscontinuity)))
      } else {
        toTransition(project, LinkStatus.UnChanged)(a)
      }
    })
    val partitioned = ProjectDeltaCalculator.partition(links, oppositeSections = Seq()).adjustedSections.keys
    partitioned.size should be(2)
    partitioned.foreach(x => {
      val (fr, to) = x
      if (fr.startMAddr == 0) {
        fr.discontinuity should be(Discontinuity.Continuous)
        to.discontinuity should be(Discontinuity.MinorDiscontinuity)
      } else {
        fr.discontinuity should be(Discontinuity.Continuous)
        to.discontinuity should be(Discontinuity.Continuous)
      }
    })
  }

  test("Test ProjectDeltaCalculator.partition When executing Multiple transfers with reversal and discontinuity change operations Then returns the correct From RoadSection -> To RoadSection mapping.") {
    val transfer = Seq((createRoadAddress(0, 502).copy(discontinuity = MinorDiscontinuity),
      createTransferProjectLink(1524, 502).copy(reversed = true)),
      (createRoadAddress(502, 1524),
        createTransferProjectLink(0, 1524).copy(discontinuity = MinorDiscontinuity, reversed = true)))
    val mapping =
      ProjectDeltaCalculator.partition(transfer).adjustedSections.keys
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
      ProjectDeltaCalculator.partition(transfer).adjustedSections.keys
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
    val addresses = (0 to 9).map(i => {
      createRoadAddress(i * 2, 2L)
    })
    val projectLinksWithCp = addresses.sortBy(_.startAddrMValue).map(a => {
      val projectLink = toProjectLink(project, LinkStatus.UnChanged)(a.copy(ely = 5))
      if (a.id == 10L)
        (a.copy(roadwayNumber = 1), projectLink.copy(calibrationPointTypes = a.calibrationPointTypes, roadwayNumber = 1))
      else if(a.id > 10L)
        (a.copy(roadwayNumber = 2), projectLink.copy(roadwayNumber = 2))
      else
        (a.copy(roadwayNumber = 1), projectLink.copy(roadwayNumber = 1))
    })
    val partitionCp = ProjectDeltaCalculator.partition(projectLinksWithCp, Seq()).adjustedSections.keys
    partitionCp.size should be(2)
    val firstSection = partitionCp.last
    val secondSection = partitionCp.head
    val cutPoint = projectLinksWithCp.find(_._2.roadwayId == 10L).get._2
    firstSection._1.startMAddr should be(projectLinksWithCp.head._2.startAddrMValue)
    firstSection._1.endMAddr should be(cutPoint.endAddrMValue)
    secondSection._1.startMAddr should be(cutPoint.endAddrMValue)
    secondSection._1.endMAddr should be(projectLinksWithCp.last._2.endAddrMValue)
  }
}
