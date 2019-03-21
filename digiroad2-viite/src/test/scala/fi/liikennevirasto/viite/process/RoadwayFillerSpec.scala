package fi.liikennevirasto.viite.process

import java.util.Properties

import fi.liikennevirasto.digiroad2.DigiroadEventBus
import fi.liikennevirasto.digiroad2.client.vvh.{VVHClient, VVHComplementaryClient, VVHRoadLinkClient, VVHSuravageClient}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.Dummies._
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.Discontinuity.Continuous
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.dao.TerminationCode.{Subsequent, Termination}
import org.joda.time.DateTime
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

class RoadwayFillerSpec extends FunSuite with Matchers with BeforeAndAfter {
  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def runWithRollback[T](f: => T): T = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      val t = f
      dynamicSession.rollback()
      t
    }
  }

  val properties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/digiroad2.properties"))
    props
  }
  val mockProjectService: ProjectService = MockitoSugar.mock[ProjectService]
  val mockRoadLinkService: RoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockRoadAddressService: RoadAddressService = MockitoSugar.mock[RoadAddressService]
  val mockEventBus: DigiroadEventBus = MockitoSugar.mock[DigiroadEventBus]
  val mockVVHClient: VVHClient = MockitoSugar.mock[VVHClient]
  val mockVVHRoadLinkClient: VVHRoadLinkClient = MockitoSugar.mock[VVHRoadLinkClient]
  val mockVVHSuravageClient: VVHSuravageClient = MockitoSugar.mock[VVHSuravageClient]
  val mockVVHComplementaryClient: VVHComplementaryClient = MockitoSugar.mock[VVHComplementaryClient]
  val projectValidator = new ProjectValidator
  val projectDAO = new ProjectDAO
  val projectLinkDAO = new ProjectLinkDAO
  val roadwayDAO = new RoadwayDAO
  val roadNetworkDAO = new RoadNetworkDAO
  val linearLocationDAO = new LinearLocationDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  val unaddressedRoadLinkDAO = new UnaddressedRoadLinkDAO
  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)
  val mockRoadwayAddressMapper: RoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]
  val roadAddressService: RoadAddressService = new RoadAddressService(mockRoadLinkService, new RoadwayDAO, new LinearLocationDAO, new RoadNetworkDAO, new UnaddressedRoadLinkDAO, mockRoadwayAddressMapper, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  val projectService: ProjectService = new ProjectService(roadAddressService, mockRoadLinkService, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  test("Test RoadwayFiller.fillRoadways() When dealing with unchanged addresses with a new Road Type in the middle of them Then check correctly assigned roadway id's."){
    withDynTransaction {
      val roadways = Map(
        (0L, dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None))
      )

      val changeInfos = Seq(
        RoadwayChangeInfo(AddressChangeType.Unchanged,
          source = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 1),

        RoadwayChangeInfo(AddressChangeType.Unchanged,
          source = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(5)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(5), reversed = false, 2),

        RoadwayChangeInfo(AddressChangeType.Unchanged,
          source = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(200L), Some(400L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(200L), Some(400L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(5), reversed = false, 3)
      )

      val projectLinks = Seq(
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 100L, Some(DateTime.now()), status = LinkStatus.UnChanged, roadType = RoadType.apply(1)),
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 100L, 200L, Some(DateTime.now()), status = LinkStatus.UnChanged, roadType = RoadType.apply(5)),
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 200L, 400L, Some(DateTime.now()), status = LinkStatus.UnChanged, roadType = RoadType.apply(1))
      )

      val changes = Seq(
        (ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos.head, DateTime.now(), Some(0)), Seq(projectLinks.head)),
        (ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos(1), DateTime.now(), Some(0)), Seq(projectLinks(1))),
        (ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos(2), DateTime.now(), Some(0)), Seq(projectLinks(2)))
      )

      val result = RoadwayFiller.fillRoadways(roadways, Map[Long, Roadway](), changes)
      result.size should be(3)
      result.head._1.size should be(2)
      result.head._1.head.roadwayNumber should not be roadways.head._2.roadwayNumber
      result(1)._1.size should be(2)
      result(1)._1.head.roadwayNumber should not be roadways.head._2.roadwayNumber
      result(2)._1.size should be(2)
      result(2)._1.head.roadwayNumber should not be roadways.head._2.roadwayNumber
    }
  }

  test("Test RoadwayFiller.fillRoadways() When dealing with newly created addresses with a new Road Type between them Then check correctly assigned roadway id's.") {
    withDynTransaction{
      val changeInfos = Seq(
        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 1),

        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(5)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(5)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(5), reversed = false, 2),

        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(200L), Some(400L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(200L), Some(400L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(5), reversed = false, 3)
      )

      val projectLinks = Seq(
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 100L, Some(DateTime.now()), status = LinkStatus.New, roadType = RoadType.apply(1)),
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 100L, 200L, Some(DateTime.now()), status = LinkStatus.New, roadType = RoadType.apply(5)),
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 200L, 400L, Some(DateTime.now()), status = LinkStatus.New, roadType = RoadType.apply(1))
      )

      val changes = Seq(
        (ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos.head, DateTime.now(), Some(0)), Seq(projectLinks.head)),
        (ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos(1), DateTime.now(), Some(0)), Seq(projectLinks(1))),
        (ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos(2), DateTime.now(), Some(0)), Seq(projectLinks(2)))
      )

      val result = RoadwayFiller.fillRoadways(Map[Long, Roadway](), Map[Long, Roadway](), changes)
      result.size should be(3)
      result.head._1.size should be(1)
      result(1)._1.size should be(1)
      result(2)._1.size should be(1)
      result.map(_._1.head.roadwayNumber).distinct.size should be(3)
    }
  }

  test("Test RoadwayFiller.fillRoadways() When dealing with unchanged at roads at the start and terminated at the end Then check correctly assigned roadway id's.") {
    withDynTransaction {
      val roadways = Map(
        (0L, dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 200L, DateTime.now(), None))
      )

      val changeInfos = Seq(
        RoadwayChangeInfo(AddressChangeType.Unchanged,
          source = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 1),

        RoadwayChangeInfo(AddressChangeType.Termination,
          source = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 2)
      )

      val projectLinks = Seq(
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 100L, Some(DateTime.now()), status = LinkStatus.UnChanged, roadType = RoadType.apply(1)),
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 100L, 200L, Some(DateTime.now()), status = LinkStatus.UnChanged, roadType = RoadType.apply(1))
      )

      val changes = Seq(
        (ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos.head, DateTime.now(), Some(0)), Seq(projectLinks.head)),
        (ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos(1), DateTime.now(), Some(0)), Seq(projectLinks(1)))
      )

      val result = RoadwayFiller.fillRoadways(roadways, Map[Long, Roadway](), changes)
      result.size should be(2)
      result.head._1.size should be(2)
      result.head._1.head.roadwayNumber should not be roadways.head._2.roadwayNumber
      result(1)._1.size should be(1)
      result(1)._1.head.roadwayNumber should not be roadways.head._2.roadwayNumber
      result(1)._1.head.terminated should be(Termination)
    }
  }

  test("Test RoadwayFiller.fillRoadways() When dealing with the termination of the first link and the transferring the remainder Then check correctly assigned roadway id's.") {
    withDynTransaction {
      val roadways = Map(
        (0L, dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 200L, DateTime.now(), None))
      )

      val changeInfos = Seq(
        RoadwayChangeInfo(AddressChangeType.Termination,
          source = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 1),

        RoadwayChangeInfo(AddressChangeType.Transfer,
          source = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 2)
      )

      val projectLinks = Seq(
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 100L, Some(DateTime.now()), status = LinkStatus.Terminated, roadType = RoadType.apply(1)),
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 100L, 200L, Some(DateTime.now()), status = LinkStatus.Transfer, roadType = RoadType.apply(1))
      )

      val changes = Seq(
        (ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos.head, DateTime.now(), Some(0)), Seq(projectLinks.head)),
        (ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos(1), DateTime.now(), Some(0)), Seq(projectLinks(1)))
      )

      val result = RoadwayFiller.fillRoadways(roadways, Map[Long, Roadway](), changes)
      result.size should be(2)
      result.head._1.size should be(1)
      result.head._1.head.roadwayNumber should not be roadways.head._2.roadwayNumber
      result.head._1.head.terminated should be(Termination)
      result(1)._1.size should be(2)
      result(1)._1.head.roadwayNumber should not be roadways.head._2.roadwayNumber
    }
  }

  test("Test RoadwayFiller.fillRoadways() When dealing with a termination in the Middle of the Roadway Then check correctly assigned roadway id's.") {
    withDynTransaction {
      val roadways = Map(
        (0L, dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 300L, DateTime.now(), None))
      )

      val changeInfos = Seq(
        RoadwayChangeInfo(AddressChangeType.Unchanged,
          source = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 1),

        RoadwayChangeInfo(AddressChangeType.Termination,
          source = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 2),

        RoadwayChangeInfo(AddressChangeType.New,
          source = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(100L), Some(500L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 3),

        RoadwayChangeInfo(AddressChangeType.Transfer,
          source = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(500L), Some(600L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 4)
      )

      val projectLinks = Seq(
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 100L, Some(DateTime.now()), status = LinkStatus.UnChanged, roadType = RoadType.apply(1)),
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 100L, 200L, Some(DateTime.now()), endDate = Some(DateTime.now()), status = LinkStatus.Terminated, roadType = RoadType.apply(1)),
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 100L, 500L, Some(DateTime.now()), status = LinkStatus.New, roadType = RoadType.apply(1)),
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 500L, 600L, Some(DateTime.now()), status = LinkStatus.Transfer, roadType = RoadType.apply(1))
      )

      val changes = Seq(
        (ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos.head, DateTime.now(), Some(0)), Seq(projectLinks.head)),
        (ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos(1), DateTime.now(), Some(0)), Seq(projectLinks(1))),
        (ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos(2), DateTime.now(), Some(0)), Seq(projectLinks(2))),
        (ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos(3), DateTime.now(), Some(0)), Seq(projectLinks(3)))
      )

      val result = RoadwayFiller.fillRoadways(roadways, Map[Long, Roadway](), changes)
      result.size should be(4)
      //Unchanged
      result.head._1.size should be(2)
      result.head._1.head.roadwayNumber should not be roadways.head._2.roadwayNumber
      //Terminated
      result(1)._1.size should be(1)
      result(1)._1.head.roadwayNumber should not be roadways.head._2.roadwayNumber
      result(1)._1.head.terminated should be(Termination)
      result(1)._1.head.endDate.isDefined should be(true)
      //New
      result(2)._1.size should be(1)
      result(2)._1.head.roadwayNumber should not be roadways.head._2.roadwayNumber
      //Transfer
      result(3)._1.size should be(2) //History row + new row
      result(3)._1.head.roadwayNumber should not be roadways.head._2.roadwayNumber
    }
  }

  test("Test RoadwayFiller.fillRoadways() When dealing with the numbering operation Then check correctly assigned roadway id's.") {
    withDynTransaction {
      val roadways = Map(
        (0L, dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 200L, DateTime.now(), None))
      )

      val changeInfos = Seq(
        RoadwayChangeInfo(AddressChangeType.ReNumeration,
          source = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(0L), Some(200L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(1L), Some(2L), Some(0L), Some(0L), Some(200L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 1)
      )

      val projectLinks = Seq(
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 100L, Some(DateTime.now()), status = LinkStatus.Numbering, roadType = RoadType.apply(1)),
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 100L, 200L, Some(DateTime.now()), status = LinkStatus.Numbering, roadType = RoadType.apply(1))
      )

      val changes = Seq(
        (ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos.head, DateTime.now(), Some(0)), projectLinks)
      )

      val result = RoadwayFiller.fillRoadways(roadways, Map[Long, Roadway](), changes)
      result.size should be(1)
      result.head._1.size should be(2)
      result.head._1.head.roadwayNumber should be(roadways.head._2.roadwayNumber)
      result.head._1.head.endDate.isDefined should be(true)
      result.head._1.head.roadwayNumber should be(result.head._1.last.roadwayNumber)
    }
  }

  test("Test RoadwayFiller.fillRoadways() When dealing with a termination of a roadway with history Then check correctly assigned roadway id's.") {
    withDynTransaction {
      val roadways = Map(
        (0L, dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 200L, DateTime.parse("1950-01-01"), None))
      )

      val historyRoadways = Map(
        (0L, dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 100L, endAddrM = 300L, DateTime.parse("1901-01-01"), Some(DateTime.parse("1950-01-01"))))
      )

      val changeInfos = Seq(
        RoadwayChangeInfo(AddressChangeType.Termination,
          source = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(0L), Some(200L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(0L), Some(200L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 1)
      )

      val projectLinks = Seq(
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 100L, Some(DateTime.now()), status = LinkStatus.Terminated, roadType = RoadType.apply(1)),
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 100L, 200L, Some(DateTime.now()), status = LinkStatus.Terminated, roadType = RoadType.apply(1))
      )

      val changes = Seq(
        (ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos.head, DateTime.now(), Some(0)), projectLinks)
      )

      val result = RoadwayFiller.fillRoadways(roadways, historyRoadways, changes)
      result.size should be(1)
      result.head._1.size should be(2)
      result.head._1.head.roadwayNumber should be(roadways.head._2.roadwayNumber)
      result.head._1.last.roadwayNumber should be(roadways.head._2.roadwayNumber)
      result.head._1.last.endDate.isDefined should be(true)
      result.head._1.head.terminated should be(Termination)
      result.head._1.last.terminated should be(Subsequent)
    }
  }

  test("Test RoadwayFiller.fillRoadways() When dealing with transfered addresses check if the end_addr_m values are correct"){
    runWithRollback {
      val roadways = Map(
        (0L, dummyRoadway(roadwayNumber = 1L, roadNumber = 9999L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)),
        (0L, dummyRoadway(roadwayNumber = 1L, roadNumber = 9999L, roadPartNumber = 2L, startAddrM = 0L, endAddrM = 1000L, DateTime.now(), None))
      )

      val changeInfos = Seq(
        RoadwayChangeInfo(AddressChangeType.Unchanged,
          source = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(1), reversed = false, 1),

        RoadwayChangeInfo(AddressChangeType.Transfer,
          source = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(100L), Some(400L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(1L), Some(2L), Some(0L), Some(0L), Some(300L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(5), reversed = false, 2),

        RoadwayChangeInfo(AddressChangeType.Transfer,
          source = dummyRoadwayChangeSection(Some(1L), Some(2L), Some(0L), Some(0L), Some(1000L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(1L), Some(2L), Some(0L), Some(300L), Some(1000L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, RoadType.apply(5), reversed = false, 3)
      )

      val projectLinks = Seq(
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 100L, Some(DateTime.now()), status = LinkStatus.UnChanged, roadType = RoadType.apply(1)),
        dummyProjectLink(1L, 2L, Track.Combined, Discontinuity.Continuous, 0L, 300L, Some(DateTime.now()), status = LinkStatus.Transfer, roadType = RoadType.apply(1)),
        dummyProjectLink(1L, 2L, Track.Combined, Discontinuity.Continuous, 300L, 1000L, Some(DateTime.now()), status = LinkStatus.Transfer, roadType = RoadType.apply(1))
      )

      val changes = Seq(
        (ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos.head, DateTime.now(), Some(0)), Seq(projectLinks.head)),
        (ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos(1), DateTime.now(), Some(0)), Seq(projectLinks(1))),
        (ProjectRoadwayChange(0L, Some("projectName"), 8, "Test", DateTime.now(), changeInfos(2), DateTime.now(), Some(0)), Seq(projectLinks(2)))
      )

      val result = RoadwayFiller.fillRoadways(roadways, Map[Long, Roadway](), changes)
      result.size should be(3)
      result.head._1.head.roadwayNumber should not be roadways.head._2.roadwayNumber
      result.head._1.head.roadPartNumber should be (1)
      result.head._1.head.endAddrMValue should be(100)
      result(1)._1.size should be(2)
      result(1)._1.last.startAddrMValue should be (0)
      result(1)._1.last.endAddrMValue should be(300)
      result(2)._1.size should be(2)
      result(2)._1.last.startAddrMValue should be (300)
      result(2)._1.last.endAddrMValue should be (1000)
    }
  }

}
