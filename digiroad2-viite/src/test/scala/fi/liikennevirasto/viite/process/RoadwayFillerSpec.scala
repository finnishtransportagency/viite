package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.DigiroadEventBus
import fi.liikennevirasto.digiroad2.asset.AdministrativeClass
import fi.liikennevirasto.digiroad2.client.vvh.{VVHClient, VVHComplementaryClient, VVHRoadLinkClient}
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.Dummies._
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.Discontinuity.Continuous
import fi.liikennevirasto.viite.dao.ProjectState.UpdatingToRoadNetwork
import fi.liikennevirasto.viite.dao.TerminationCode._
import fi.liikennevirasto.viite.dao._
import org.joda.time.DateTime
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

class RoadwayFillerSpec extends FunSuite with Matchers with BeforeAndAfter {
  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)

  def runWithRollback[T](f: => T): T = {
    Database.forDataSource(PostGISDatabase.ds).withDynTransaction {
      val t = f
      dynamicSession.rollback()
      t
    }
  }

  val mockProjectService: ProjectService = MockitoSugar.mock[ProjectService]
  val mockRoadLinkService: RoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockRoadAddressService: RoadAddressService = MockitoSugar.mock[RoadAddressService]
  val mockNodesAndJunctionsService = MockitoSugar.mock[NodesAndJunctionsService]
  val mockEventBus: DigiroadEventBus = MockitoSugar.mock[DigiroadEventBus]
  val mockVVHClient: VVHClient = MockitoSugar.mock[VVHClient]
  val mockVVHRoadLinkClient: VVHRoadLinkClient = MockitoSugar.mock[VVHRoadLinkClient]
  val mockVVHComplementaryClient: VVHComplementaryClient = MockitoSugar.mock[VVHComplementaryClient]
  val projectValidator = new ProjectValidator
  val projectDAO = new ProjectDAO
  val projectLinkDAO = new ProjectLinkDAO
  val roadwayDAO = new RoadwayDAO
  val roadNetworkDAO = new RoadNetworkDAO
  val linearLocationDAO = new LinearLocationDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  val nodeDAO = new NodeDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val nodePointDAO = new NodePointDAO
  val junctionPointDAO = new JunctionPointDAO
  val roadwayChangesDAO = new RoadwayChangesDAO
  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)
  val mockRoadwayAddressMapper: RoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]
  val roadAddressService: RoadAddressService = new RoadAddressService(mockRoadLinkService, roadwayDAO, linearLocationDAO,
    roadNetworkDAO, roadwayPointDAO, nodePointDAO, junctionPointDAO, mockRoadwayAddressMapper, mockEventBus, frozenVVH = false) {

    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  val projectService: ProjectService = new ProjectService(roadAddressService, mockRoadLinkService, mockNodesAndJunctionsService, roadwayDAO,
    roadwayPointDAO, linearLocationDAO, projectDAO, projectLinkDAO,
    nodeDAO, nodePointDAO, junctionPointDAO, projectReservedPartDAO, roadwayChangesDAO,
    roadwayAddressMapper, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  test("Test RoadwayFiller.fillRoadways() When dealing with unchanged addresses with a new administrative class in the middle of them Then check correctly assigned roadway id's."){
    runWithRollback {
      val roadways = Map(
        (0L, dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None))
      )

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(),
                                  Seq(ProjectReservedPart(0L, 1L, 1L,  None, None,  None, None,  None, None, None)),
                                  Seq(), None)
      projectDAO.create(project)

      /* Note: Projectlinks should have different roadwaynumbers as project calculation will assign new roadwaynumbers and applyRoadwayChanges() assumes so. */
      val projectLinks = Seq(
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 100L, Some(DateTime.now()), status = LinkStatus.UnChanged, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 10),
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 100L, 200L, Some(DateTime.now()), status = LinkStatus.UnChanged, administrativeClass = AdministrativeClass.apply(3), roadwayNumber = 20),
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 200L, 400L, Some(DateTime.now()), status = LinkStatus.UnChanged, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 30)
      )

      val roadwayChanges = roadways.values.map(r => RoadwayFiller.RwChanges(r, Seq.empty[Roadway], projectLinks.filter(_.roadwayId == r.id))).toSeq
      val generatedRoadways = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty).head._1.groupBy(_.roadwayNumber).values

      generatedRoadways.size should be(3)
      generatedRoadways.foreach(gr => {
        gr.size should be(2)
        gr.find(!_.endDate.isDefined).get.roadwayNumber should not be roadways.head._2.roadwayNumber
      })
    }
  }

  test("Test RoadwayFiller.fillRoadways() When dealing with newly created addresses with a new administrative class between them Then check correctly assigned roadway id's.") {
    withDynTransaction{
      val projectLinks = Seq(
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 100L, Some(DateTime.now()), status = LinkStatus.New, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = NewIdValue),
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 100L, 200L, Some(DateTime.now()), status = LinkStatus.New, administrativeClass = AdministrativeClass.apply(3), roadwayNumber = NewIdValue+1),
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 200L, 400L, Some(DateTime.now()), status = LinkStatus.New, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = NewIdValue+2)
      )

      val result = RoadwayFiller.applyNewLinks(projectLinks)

      result.size should be(3)
      result.head._1.size should be(1)
      result(1)._1.size should be(1)
      result(2)._1.size should be(1)
      result.map(_._1.head.roadwayNumber).distinct.size should be(3)
    }
  }

  test("Test RoadwayFiller.fillRoadways() When dealing with unchanged at roads at the start and terminated at the end Then check correctly assigned roadway id's.") {
    runWithRollback {
      val roadways = Map(
        (0L, dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 200L, DateTime.now(), None))
      )

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(),
                                  Seq(ProjectReservedPart(0L, 1L, 1L,  None, None,  None, None,  None, None, None)),
                                  Seq(), None)
      projectDAO.create(project)

      val projectLinks = Seq(
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 100L, Some(DateTime.now()), status = LinkStatus.UnChanged, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 10),
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 100L, 200L, Some(DateTime.now()), status = LinkStatus.Terminated, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 20)
      )

      val roadwayChanges = roadways.values.map(r => RoadwayFiller.RwChanges(r, Seq.empty[Roadway], projectLinks)).toSeq
      val result = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty)

      result.size should be(2)
      result.head._1.size should be(2)
      result.head._1.head.roadwayNumber should not be roadways.head._2.roadwayNumber
      result(1)._1.size should be(1)
      result(1)._1.head.roadwayNumber should not be roadways.head._2.roadwayNumber
      result(1)._1.head.terminated.value should be(TerminationCode.Termination.value)
    }
  }

  test("Test RoadwayFiller.fillRoadways() When dealing with the termination of the first link and the transferring the remainder Then check correctly assigned roadway id's.") {
    runWithRollback {
      val roadways = Map(
        (0L, dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 200L, DateTime.now(), None))
      )

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(),
                                  Seq(ProjectReservedPart(0L, 1L, 1L,  None, None,  None, None,  None, None, None)),
                                  Seq(), None)
      projectDAO.create(project)

      val projectLinks = Seq(
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 100L, Some(DateTime.now()), status = LinkStatus.Terminated, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 10),
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 100L, 200L, Some(DateTime.now()), status = LinkStatus.Transfer, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 20)
      )

      val roadwayChanges = roadways.values.map(r => RoadwayFiller.RwChanges(r, Seq.empty[Roadway], projectLinks)).toSeq
      val result = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty).sortBy(_._1.size)

      result.size should be(2)
      result.head._1.size should be(1)
      result.head._1.head.roadwayNumber should not be roadways.head._2.roadwayNumber
      result.head._1.head.terminated.value should be(TerminationCode.Termination.value)
      result(1)._1.size should be(2)
      result(1)._1.head.roadwayNumber should not be roadways.head._2.roadwayNumber
    }
  }

  test("Test RoadwayFiller.fillRoadways() When dealing with a termination in the Middle of the Roadway Then check correctly assigned roadway id's.") {
    runWithRollback {
      val roadways = Map(
        (0L, dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 300L, DateTime.now(), None))
      )

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(),
                                  Seq(ProjectReservedPart(0L, 1L, 1L,  None, None,  None, None,  None, None, None)),
                                  Seq(), None)
      projectDAO.create(project)

      val projectLinks = Seq(
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 100L, Some(DateTime.now()), status = LinkStatus.UnChanged, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 10),
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 100L, 200L, Some(DateTime.now()), endDate = Some(DateTime.now()), status = LinkStatus.Terminated, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 20),
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 100L, 500L, Some(DateTime.now()), status = LinkStatus.New, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 30),
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 500L, 600L, Some(DateTime.now()), status = LinkStatus.Transfer, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 40)
      )

      val roadwayChanges = roadways.values.map(r => RoadwayFiller.RwChanges(r, Seq.empty[Roadway], projectLinks.filterNot(_.status == LinkStatus.New))).toSeq
      val result2 = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty)
      val result3 = RoadwayFiller.applyNewLinks(projectLinks.filter(_.status == LinkStatus.New))
      val result = (result2.flatMap(_._1) ++ result3.flatMap(_._1)).groupBy(_.roadwayNumber).values.toSeq.sortBy(_.head.endAddrMValue)

      result.size should be(4)
      //Unchanged
      result.head.size should be(2)
      result.head.head.roadwayNumber should not be roadways.head._2.roadwayNumber
      //Terminated
      result(1).size should be(1)
      result(1).head.roadwayNumber should not be roadways.head._2.roadwayNumber
      result(1).head.terminated.value should be(TerminationCode.Termination.value)
      result(1).head.endDate.isDefined should be(true)
      //New
      result(2).size should be(1)
      result(2).head.roadwayNumber should not be roadways.head._2.roadwayNumber
      //Transfer
      result(3).size should be(2) //History row + new row
      result(3).head.roadwayNumber should not be roadways.head._2.roadwayNumber
    }
  }

  test("Test RoadwayFiller.fillRoadways() When dealing with the numbering operation Then check correctly assigned roadway id's.") {
    withDynTransaction {
      val roadways = Map(
        (0L, dummyRoadway(roadwayNumber = 1L, roadNumber = 1L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 200L, DateTime.now(), None))
      )

      val changeInfos = Seq(
        RoadwayChangeInfo(AddressChangeType.ReNumeration,
          source = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(0L), Some(200L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(1L), Some(2L), Some(0L), Some(0L), Some(200L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, AdministrativeClass.apply(1), reversed = false, 1)
      )

      val projectLinks = Seq(
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 100L, Some(DateTime.now()), status = LinkStatus.Numbering, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = roadways.head._2.roadwayNumber),
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 100L, 200L, Some(DateTime.now()), status = LinkStatus.Numbering, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = roadways.head._2.roadwayNumber)
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
          source = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(0L), Some(200L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(0L), Some(200L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, AdministrativeClass.apply(1), reversed = false, 1)
      )

      val projectLinks = Seq(
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 100L, Some(DateTime.now()), status = LinkStatus.Terminated, administrativeClass = AdministrativeClass.apply(1)),
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 100L, 200L, Some(DateTime.now()), status = LinkStatus.Terminated, administrativeClass = AdministrativeClass.apply(1))
      )

      val roadwayChanges = roadways.values.map(r => RoadwayFiller.RwChanges(r, historyRoadways.map(_._2).toSeq, projectLinks.filterNot(_.status == LinkStatus.New))).toSeq
      val result = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty)

      result.size should be(1)
      result.head._1.size should be(2)
      result.head._1.head.roadwayNumber should be(roadways.head._2.roadwayNumber)
      result.head._1.last.roadwayNumber should be(roadways.head._2.roadwayNumber)
      result.head._1.last.endDate.isDefined should be(true)
      result.head._1.head.terminated.value should be(TerminationCode.Termination.value)
      result.head._1.last.terminated should be(Subsequent)
    }
  }

  test("Test RoadwayFiller.fillRoadways() When dealing with transferred addresses check if the end_addr_m values are correct"){
    runWithRollback {
      val roadways = Map(
        (0L, dummyRoadway(roadwayNumber = 1L, roadNumber = 9999L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 400L, DateTime.now(), None)),
        (0L, dummyRoadway(roadwayNumber = 1L, roadNumber = 9999L, roadPartNumber = 2L, startAddrM = 0L, endAddrM = 1000L, DateTime.now(), None))
      )

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(),
                                  Seq(ProjectReservedPart(0L, 9999L, 1L,  None, None,  None, None,  None, None, None),
                                      ProjectReservedPart(0L, 9999L, 2L,  None, None,  None, None,  None, None, None)),
                                  Seq(), None)
      projectDAO.create(project)

      val projectLinks = Seq(
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 100L, Some(DateTime.now()), status = LinkStatus.UnChanged, administrativeClass = AdministrativeClass.apply(1),roadwayNumber = 10),
        dummyProjectLink(1L, 2L, Track.Combined, Discontinuity.Continuous, 0L, 300L, Some(DateTime.now()), status = LinkStatus.Transfer, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 20),
        dummyProjectLink(1L, 2L, Track.Combined, Discontinuity.Continuous, 300L, 1000L, Some(DateTime.now()), status = LinkStatus.Transfer, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 30)
      )

      val roadwayChanges = roadways.values.map(r => RoadwayFiller.RwChanges(r, Seq.empty[Roadway], projectLinks.filterNot(_.status == LinkStatus.New))).toSeq
      val result2 = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty).head._1.sortBy(r=> (r.startAddrMValue,r.roadPartNumber))
      val result = result2.groupBy(_.roadwayNumber).values.toSeq.sortBy(_.sortBy(_.startAddrMValue).head.endAddrMValue).toList

      result.size should be(3)
      result.head.head.roadwayNumber should not be roadways.head._2.roadwayNumber
      result.head.head.roadPartNumber should be (1)
      result.head.head.endAddrMValue should be(100)
      result(1).size should be(2)
      result(1).last.startAddrMValue should be (0)
      result(1).last.endAddrMValue should be(300)
      result(2).size should be(2)
      result(2).last.startAddrMValue should be (300)
      result(2).last.endAddrMValue should be (1000)
    }
  }

  test("Test RoadwayFiller.fillRoadways() When dealing with Unchanged + Transfer with same properties and same roadwayNumber then they should be merged into one"){
    runWithRollback {
      val roadwayNumber1 = 1

      val roadways = Map(
        (0L, dummyRoadway(roadwayNumber = 1L, roadNumber = 9999L, roadPartNumber = 1L, startAddrM = 0L, endAddrM = 300L, DateTime.now(), None))
      )

      val changeInfos = Seq(
        RoadwayChangeInfo(AddressChangeType.Unchanged,
          source = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.Continuous), Some(8L)),
          target = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(0L), Some(100L), Some(AdministrativeClass.apply(2)), Some(Discontinuity.Continuous), Some(8L)),
          Continuous, AdministrativeClass.apply(1), reversed = false, 1),

        RoadwayChangeInfo(AddressChangeType.Transfer,
          source = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(100L), Some(300L), Some(AdministrativeClass.apply(1)), Some(Discontinuity.EndOfRoad), Some(8L)),
          target = dummyRoadwayChangeSection(Some(1L), Some(1L), Some(0L), Some(100L), Some(300L), Some(AdministrativeClass.apply(2)), Some(Discontinuity.EndOfRoad), Some(8L)),
          Continuous, AdministrativeClass.apply(2), reversed = false, 2)
      )

      val projectLinks = Seq(
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 0L, 100L, Some(DateTime.now()), status = LinkStatus.UnChanged, administrativeClass = AdministrativeClass.apply(1)).copy(ely = 0, roadwayNumber = roadwayNumber1),
        dummyProjectLink(1L, 1L, Track.Combined, Discontinuity.Continuous, 100L, 300L, Some(DateTime.now()), status = LinkStatus.Transfer, administrativeClass = AdministrativeClass.apply(1)).copy(ely = 0, roadwayNumber = roadwayNumber1)
      )

      val roadwayChanges = roadways.values.map(r => RoadwayFiller.RwChanges(r, Seq.empty[Roadway], projectLinks.filterNot(_.status == LinkStatus.New))).toSeq
      val result = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty).head._1.sortBy(r=> (r.startAddrMValue,r.roadPartNumber))

      result.size should be(1)
    }
  }

}
