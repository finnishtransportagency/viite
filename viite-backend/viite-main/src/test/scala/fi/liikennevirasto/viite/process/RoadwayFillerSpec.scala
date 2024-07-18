package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.DigiroadEventBus
import fi.liikennevirasto.digiroad2.client.kgv.{KgvRoadLink, KgvRoadLinkClient}
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.Dummies._
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.dao.ProjectState.UpdatingToRoadNetwork
import fi.liikennevirasto.viite.dao.TerminationCode._
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, Discontinuity, RoadAddressChangeType, RoadLink, RoadPart, Track}
import fi.vaylavirasto.viite.postgis.PostGISDatabase.runWithRollback
import org.joda.time.DateTime
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import org.scalatest.mock.MockitoSugar

class RoadwayFillerSpec extends FunSuite with Matchers with BeforeAndAfter {

  val mockProjectService: ProjectService = MockitoSugar.mock[ProjectService]
  val mockRoadLinkService: RoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockRoadAddressService: RoadAddressService = MockitoSugar.mock[RoadAddressService]
  val mockNodesAndJunctionsService = MockitoSugar.mock[NodesAndJunctionsService]
  val mockEventBus: DigiroadEventBus = MockitoSugar.mock[DigiroadEventBus]
  val mockKGVClient: KgvRoadLink = MockitoSugar.mock[KgvRoadLink]
  val mockKGVRoadLinkClient: KgvRoadLinkClient[RoadLink] = MockitoSugar.mock[KgvRoadLinkClient[RoadLink]]
//  val mockVVHComplementaryClient: VVHComplementaryClient = MockitoSugar.mock[VVHComplementaryClient]
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

  val roadAddressService: RoadAddressService =
    new RoadAddressService(
                            mockRoadLinkService,
                            roadwayDAO,
                            linearLocationDAO,
                            roadNetworkDAO,
                            roadwayPointDAO,
                            nodePointDAO,
                            junctionPointDAO,
                            mockRoadwayAddressMapper,
                            mockEventBus,
                            frozenKGV = false
                            ) {
                                override def withDynSession[T](f: => T): T = f
                                override def withDynTransaction[T](f: => T): T = f
                              }

  val projectService: ProjectService =
    new ProjectService(
                        roadAddressService,
                        mockRoadLinkService,
                        mockNodesAndJunctionsService,
                        roadwayDAO,
                        roadwayPointDAO,
                        linearLocationDAO,
                        projectDAO,
                        projectLinkDAO,
                        nodeDAO,
                        nodePointDAO,
                        junctionPointDAO,
                        projectReservedPartDAO,
                        roadwayChangesDAO,
                        roadwayAddressMapper,
                        mockEventBus
                        ) {
                        override def withDynSession[T](f: => T): T = f
                        override def withDynTransaction[T](f: => T): T = f
                      }

  test("Test RoadwayFiller.applyRoadwayChanges() #Confluence: Change in the Middle of the Roadway. " +
                "When dealing with unchanged addresses with a new administrative class in the middle of them " +
                "Then check correctly assigned roadway id's."){
    runWithRollback {
      val roadways = Map(
        (0L, dummyRoadway(roadwayNumber = 1L, roadPart = RoadPart(1, 1), addrMRange = AddrMRange(0L, 400L), DateTime.now(), None))
      )

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(),
                                  Seq(ProjectReservedPart(0L, RoadPart(1, 1), None, None, None, None, None, None, None)),
                                  Seq(), None)
      projectDAO.create(project)

      /* Note: Projectlinks should have different roadwaynumbers as project calculation will assign new roadwaynumbers and applyRoadwayChanges() assumes so. */
      val projectLinks = Seq(
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(  0L, 100L), Some(DateTime.now()), status = RoadAddressChangeType.Unchanged, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 10),
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(100L, 200L), Some(DateTime.now()), status = RoadAddressChangeType.Unchanged, administrativeClass = AdministrativeClass.apply(3), roadwayNumber = 20),
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(200L, 400L), Some(DateTime.now()), status = RoadAddressChangeType.Unchanged, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 30)
      )

      val roadwayChanges = roadways.values.map(r => RoadwayFiller.RwChanges(r, Seq.empty[Roadway], projectLinks.filter(_.roadwayId == r.id))).toSeq
      val generatedRoadways = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty).head._1.groupBy(_.roadwayNumber).values

      generatedRoadways.size should be(3)
      generatedRoadways.foreach(gr => {
        gr.size should be(2)
        gr.find(_.endDate.isEmpty).get.roadwayNumber should not be roadways.head._2.roadwayNumber
      })
    }
  }

  test("Test RoadwayFiller.applyNewLinks() When dealing with newly created addresses with a new administrative class between them Then check correctly assigned roadway id's.") {
    runWithRollback {
      val projectLinks = Seq(
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(  0L, 100L), Some(DateTime.now()), status = RoadAddressChangeType.New, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = NewIdValue),
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(100L, 200L), Some(DateTime.now()), status = RoadAddressChangeType.New, administrativeClass = AdministrativeClass.apply(3), roadwayNumber = NewIdValue+1),
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(200L, 400L), Some(DateTime.now()), status = RoadAddressChangeType.New, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = NewIdValue+2)
      )

      val result = RoadwayFiller.applyNewLinks(projectLinks)

      result.size should be(3)
      result.head._1.size should be(1)
      result(1)._1.size should be(1)
      result(2)._1.size should be(1)
      result.map(_._1.head.roadwayNumber).distinct.size should be(3)
    }
  }

  test("Test RoadwayFiller.applyRoadwayChanges() #Confluence: Change in the Middle of the Roadway. " +
                "When dealing with one unchanged road having no history with administrative class change in the middle " +
                "Then should return 3 new roadways and one new history row.") {
    runWithRollback {
      val roadway = dummyRoadway(roadwayNumber = 1L, roadPart = RoadPart(1, 1), addrMRange = AddrMRange(0L, 400L), DateTime.now(), None).copy(discontinuity = Discontinuity.EndOfRoad, ely = 8)
      roadwayDAO.create(Seq(roadway))

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(), Seq(ProjectReservedPart(0L, RoadPart(1, 1), None, None, None, None, None, None, None)), Seq(), None)
      projectDAO.create(project)

      val projectLinks = Seq(
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(  0L, 100L), Some(DateTime.now()), status = RoadAddressChangeType.Unchanged, administrativeClass = AdministrativeClass.State,        roadwayNumber = NewIdValue),
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(100L, 200L), Some(DateTime.now()), status = RoadAddressChangeType.Unchanged, administrativeClass = AdministrativeClass.Municipality, roadwayNumber = NewIdValue+1),
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.EndOfRoad,  AddrMRange(200L, 400L), Some(DateTime.now()), status = RoadAddressChangeType.Unchanged, administrativeClass = AdministrativeClass.State,        roadwayNumber = NewIdValue+2)
      )

      val changeAdminClassProjectLink = projectLinks(1)
      val roadwayChanges              = Seq(RoadwayFiller.RwChanges(roadway, Seq.empty[Roadway], projectLinks))
      val result                      = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty)

      result should have size 1

      // Check Test assertion 3 new roadways and one new history row.
      val resultHead = result.head
      resultHead._1 should have size 4
      resultHead._2 should have size 3
      resultHead._3 should have size 3

      val newRoadways = resultHead._1.filter(r => r.endDate.isEmpty && r.validTo.isEmpty).sortBy(_.addrMRange.start)
      newRoadways should have size 3
      projectLinks.sortBy(_.addrMRange.start).map(_.addrMRange) should be(newRoadways.map(_.addrMRange))
      newRoadways.map(_.administrativeClass) should be(List(AdministrativeClass.State, AdministrativeClass.Municipality, AdministrativeClass.State))

      val historyRoadways = resultHead._1.filter(r => r.endDate.nonEmpty && r.validTo.isEmpty)
      historyRoadways should have size 1
      historyRoadways.head.administrativeClass should be(roadway.administrativeClass)
      historyRoadways.head.discontinuity       should be(changeAdminClassProjectLink.discontinuity)
      historyRoadways.head.addrMRange          should be(changeAdminClassProjectLink.addrMRange)
    }
  }

  test("Test RoadwayFiller.applyRoadwayChanges() #Confluence: Transfer the Roadway. " +
                 "When transfering a single roadway" +
                 "Then old roadway is expired and a new is created with new address and old roadway number.") {
    runWithRollback{
      val roadwayNumber = 1L
      val roadway = dummyRoadway(roadwayNumber = roadwayNumber, roadPart = RoadPart(1, 1), addrMRange = AddrMRange(0L, 400L), DateTime.now().minusDays(2), None).copy(ely = 8)
      roadwayDAO.create(Seq(roadway))

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(), Seq(ProjectReservedPart(0L, RoadPart(1, 1), None, None, None, None, None, None, None)), Seq(), None)
      projectDAO.create(project)

      val projectLinks = Seq(
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(100L, 500L), Some(DateTime.now()), status = RoadAddressChangeType.Renumeration, administrativeClass = AdministrativeClass.State, roadwayNumber = roadwayNumber).copy(originalAddrMRange = roadway.addrMRange)
      )

      val roadwayChanges = Seq(RoadwayFiller.RwChanges(roadway, Seq.empty[Roadway], projectLinks))
      val result         = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty)

      result should have size 1

      // Check old roadway is expired and a new is created .
      val resultHead = result.head
      resultHead._1 should have size 2
      resultHead._2 should have size 1
      resultHead._3 should have size 1

      val historyRoadways = resultHead._1.filter(r => r.endDate.nonEmpty && r.validTo.isEmpty)
      historyRoadways should have size 1
      val newRoadways = resultHead._1.filter(r => r.endDate.isEmpty && r.validTo.isEmpty).sortBy(_.addrMRange.start)
      newRoadways should have size 1

      newRoadways.head.roadwayNumber should be(roadwayNumber)
      newRoadways.head.roadwayNumber should be(historyRoadways.head.roadwayNumber)

      projectLinks.map(_.addrMRange) should be(newRoadways.map(_.addrMRange))

      historyRoadways.head.addrMRange should be(roadway.addrMRange)
    }
  }

  test("Test RoadwayFiller.applyRoadwayChanges() #Confluence: Numbering the Roadway. " +
    "When numbering a single roadway's road part number" +
    "Then history row is created and a new row is created with new address and old roadway number.") {
    runWithRollback{
      val roadwayNumber = 1L
      val roadway = dummyRoadway(roadwayNumber = roadwayNumber, roadPart = RoadPart(1, 1), addrMRange = AddrMRange(0L, 400L), DateTime.now().minusDays(2), None).copy(ely = 8)
      roadwayDAO.create(Seq(roadway))

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(), Seq(ProjectReservedPart(0L, RoadPart(1, 1), None, None, None, None, None, None, None)), Seq(), None)
      projectDAO.create(project)

      val projectLinks = Seq(
        dummyProjectLink(RoadPart(1, 2), Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 400L), Some(DateTime.now()), status = RoadAddressChangeType.Renumeration, administrativeClass = AdministrativeClass.State, roadwayNumber = roadwayNumber).copy(originalAddrMRange = roadway.addrMRange)
      )

      val roadwayChanges = Seq(RoadwayFiller.RwChanges(roadway, Seq.empty[Roadway], projectLinks))
      val result         = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty)

      result should have size 1

      // Check old roadway is expired and a new is created .
      val resultHead = result.head
      resultHead._1 should have size 2
      resultHead._2 should have size 1
      resultHead._3 should have size 1

      val historyRoadways = resultHead._1.filter(r => r.endDate.nonEmpty && r.validTo.isEmpty)
      historyRoadways should have size 1
      val newRoadways = resultHead._1.filter(r => r.endDate.isEmpty && r.validTo.isEmpty).sortBy(_.addrMRange.start)
      newRoadways should have size 1

      newRoadways.head.roadwayNumber should be(roadwayNumber)
      newRoadways.head.roadwayNumber should be(historyRoadways.head.roadwayNumber)

      projectLinks.map(_.addrMRange) should be(newRoadways.map(_.addrMRange))

      historyRoadways.head.addrMRange should be(roadway.addrMRange)
    }
  }

  test("Test RoadwayFiller.applyRoadwayChanges() #Confluence: Transfering the Roadway. " +
    "When transfering a single roadway to a different ELY" +
    "Then history row is created and a new row is created with new address and old roadway number.") {
    runWithRollback{
      val roadwayNumber = 1L
      val roadway = dummyRoadway(roadwayNumber = roadwayNumber, roadPart = RoadPart(1, 1), addrMRange = AddrMRange(0L, 400L), DateTime.now().minusDays(2), None).copy(ely = 8)
      roadwayDAO.create(Seq(roadway))

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(), Seq(ProjectReservedPart(0L, RoadPart(1, 1), None, None, None, None, None, None, None)), Seq(), None)
      projectDAO.create(project)

      val projectLinks = Seq(
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 400L), Some(DateTime.now()), status = RoadAddressChangeType.Transfer, administrativeClass = AdministrativeClass.State, roadwayNumber = roadwayNumber).copy(originalAddrMRange = roadway.addrMRange, ely = 10)
      )

      val roadwayChanges = Seq(RoadwayFiller.RwChanges(roadway, Seq.empty[Roadway], projectLinks))
      val result         = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty)

      result should have size 1

      // Check old roadway is expired and a new is created .
      val resultHead = result.head
      resultHead._1 should have size 2
      resultHead._2 should have size 1
      resultHead._3 should have size 1

      val historyRoadways = resultHead._1.filter(r => r.endDate.nonEmpty && r.validTo.isEmpty)
      historyRoadways should have size 1
      val newRoadways = resultHead._1.filter(r => r.endDate.isEmpty && r.validTo.isEmpty).sortBy(_.addrMRange.start)
      newRoadways should have size 1

      newRoadways.head.roadwayNumber should be(roadwayNumber)
      newRoadways.head.roadwayNumber should be(historyRoadways.head.roadwayNumber)
      newRoadways.head.ely should be(10)

      projectLinks.map(_.addrMRange)  should be(newRoadways.map(_.addrMRange))

      historyRoadways.head.addrMRange should be(roadway.addrMRange)
      historyRoadways.head.ely        should be(8)
    }
  }

  test("Test RoadwayFiller.applyRoadwayChanges() #Confluence: Numbering the Roadway. " +
    "When numbering a single roadway's road number" +
    "Then history row is created and a new row is created with new address and old roadway number.") {
    runWithRollback{
      val roadwayNumber = 1L
      val roadway = dummyRoadway(roadwayNumber = roadwayNumber, roadPart = RoadPart(1, 1), addrMRange = AddrMRange(0L, 400L), DateTime.now(), None).copy(ely = 8)
      roadwayDAO.create(Seq(roadway))

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(), Seq(ProjectReservedPart(0L, RoadPart(1, 1), None, None, None, None, None, None, None)), Seq(), None)
      projectDAO.create(project)

      val projectLinks = Seq(
        dummyProjectLink(RoadPart(2, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 400L), Some(DateTime.now()), status = RoadAddressChangeType.Transfer, administrativeClass = AdministrativeClass.State, roadwayNumber = roadwayNumber).copy(originalAddrMRange = roadway.addrMRange)
      )

      val roadwayChanges = Seq(RoadwayFiller.RwChanges(roadway, Seq.empty[Roadway], projectLinks))
      val result         = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty)

      result should have size 1

      // Check old roadway is expired and a new is created .
      val resultHead = result.head
      resultHead._1 should have size 2
      resultHead._2 should have size 1
      resultHead._3 should have size 1

      val historyRoadways = resultHead._1.filter(r => r.endDate.nonEmpty && r.validTo.isEmpty)
      historyRoadways should have size 1
      val newRoadways = resultHead._1.filter(r => r.endDate.isEmpty && r.validTo.isEmpty).sortBy(_.addrMRange.start)
      newRoadways should have size 1

      newRoadways.head.roadwayNumber should be(roadwayNumber)
      newRoadways.head.roadwayNumber should be(historyRoadways.head.roadwayNumber)

      projectLinks.map(_.addrMRange) should be(newRoadways.map(_.addrMRange))

      historyRoadways.head.addrMRange should be(roadway.addrMRange)
    }
  }

  test("Test RoadwayFiller.applyRoadwayChanges() #Confluence: Extending the Road Part with a New Roadway. " +
                 "When Extending the Road Part with a New Roadway" +
                 "Then old roadway is unchanged and a new is created with new address and roadway number.") {
    runWithRollback{
      val roadwayNumber = 1L
      val roadway = dummyRoadway(roadwayNumber = roadwayNumber, roadPart = RoadPart(1, 1), addrMRange = AddrMRange(100L, 170L), DateTime.now(), None).copy(ely = 8)
      roadwayDAO.create(Seq(roadway))

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(), Seq(ProjectReservedPart(0L, RoadPart(1, 1), None, None, None, None, None, None, None)), Seq(), None)
      projectDAO.create(project)

      val unChangedProjectLink = dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(100L, 170L), Some(DateTime.now()), status = RoadAddressChangeType.Unchanged, administrativeClass = AdministrativeClass.State, roadwayNumber = roadwayNumber).copy(originalAddrMRange = roadway.addrMRange)
      val newProjectLink       = dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(170L, 200L), Some(DateTime.now()), status = RoadAddressChangeType.New,       administrativeClass = AdministrativeClass.State)

      val roadwayChanges = Seq(RoadwayFiller.RwChanges(roadway, Seq.empty[Roadway], Seq(unChangedProjectLink)))
      val resultForUnchanged = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty)

      val resultHead = resultForUnchanged.head
      resultHead._1 should have size 1
      resultHead._2 should have size 1
      resultHead._3 should have size 1

      val historyRoadway = resultHead._1.filter(r => r.endDate.isEmpty && r.validTo.isEmpty)
      historyRoadway should have size 1
      historyRoadway.map(r => (r.addrMRange, r.roadwayNumber, r.roadPart)) should be(Seq(roadway).map(r => (r.addrMRange, r.roadwayNumber, r.roadPart)))

      val roadwayForNew = RoadwayFiller.applyNewLinks(Seq(newProjectLink))
      roadwayForNew should have size 1

      val resultForNewHead = roadwayForNew.head
      resultForNewHead._1 should have size 1
      resultForNewHead._2 should have size 1
      resultForNewHead._3 should have size 1

      resultForNewHead._1.map(r => (r.addrMRange, r.roadwayNumber, r.roadPart)) should be(Seq(newProjectLink).map(pl => (pl.addrMRange, pl.roadwayNumber, pl.roadPart)))
    }
  }

  test("Test RoadwayFiller.applyRoadwayChanges() #Confluence: Discontinuity in the Middle of the Roadway. " +
                 "When Discontinuity in the Middle of the Roadway" +
                 "Then two new roadways are created and one expired.") {
    runWithRollback{
      val roadwayNumber = 1L
      val roadway = dummyRoadway(roadwayNumber = roadwayNumber, roadPart = RoadPart(1, 1), addrMRange = AddrMRange(0L, 400L), DateTime.now(), None).copy(ely = 8)
      roadwayDAO.create(Seq(roadway))

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(), Seq(ProjectReservedPart(0L, RoadPart(1, 1), None, None, None, None, None, None, None)), Seq(), None)
      projectDAO.create(project)

      val projectLinks = Seq(
      dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.MinorDiscontinuity,AddrMRange(  0L, 200L), Some(DateTime.now()), status = RoadAddressChangeType.Unchanged, administrativeClass = AdministrativeClass.State, roadwayNumber = roadwayNumber+1),
      dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous,        AddrMRange(200L, 400L), Some(DateTime.now()), status = RoadAddressChangeType.New,       administrativeClass = AdministrativeClass.State, roadwayNumber = roadwayNumber+2)
      )

      val roadwayChanges = Seq(RoadwayFiller.RwChanges(roadway, Seq.empty[Roadway], projectLinks))
      val resultForUnchanged = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty)

      // Two new roadways are created and one expired.
      val resultHead = resultForUnchanged.head
      resultHead._1 should have size 3
      resultHead._2 should have size 2
      resultHead._3 should have size 2

      val createdRoadways = resultHead._1.filter(r => r.endDate.isEmpty && r.validTo.isEmpty)
      val (rw1, rw2) = createdRoadways.partition(_.addrMRange.start == 0)

      rw1 should have size 1
      rw2 should have size 1

      rw1.head.discontinuity should be(projectLinks.head.discontinuity)
      rw1.head.roadwayNumber should be(projectLinks.head.roadwayNumber)
      rw1.head.addrMRange    should be(projectLinks.head.addrMRange)

      rw2.head.discontinuity should be(projectLinks.last.discontinuity)
      rw2.head.roadwayNumber should be(projectLinks.last.roadwayNumber)
      rw2.head.addrMRange    should be(projectLinks.last.addrMRange)

      val historyRoadway = resultHead._1.filter(r => r.endDate.nonEmpty && r.validTo.isEmpty)
      historyRoadway should have size 1
      historyRoadway.head.discontinuity should be(roadway.discontinuity)
      historyRoadway.head.roadwayNumber should be(projectLinks.head.roadwayNumber)
      historyRoadway.head.addrMRange    should be(projectLinks.head.addrMRange)
    }
  }

  test("Test RoadwayFiller.applyRoadwayChanges() #Confluence: Roadway from One Track to Two Tracks. " +
                 "When a roadway is splitted from combined to combined and two track roadway" +
                 "Then three new roadways are created and one expired.") {
    runWithRollback{
      val roadwayNumber = 1L
      val roadway = dummyRoadway(roadwayNumber = roadwayNumber, roadPart = RoadPart(1, 1), addrMRange = AddrMRange(0L, 400L), DateTime.now(), None).copy(ely = 8)
      roadwayDAO.create(Seq(roadway))

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(), Seq(ProjectReservedPart(0L, RoadPart(1, 1), None, None, None, None, None, None, None)), Seq(), None)
      projectDAO.create(project)

      val projectLinks = Seq(
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(  0L, 200L), Some(DateTime.now()), status = RoadAddressChangeType.Unchanged, administrativeClass = AdministrativeClass.State, roadwayNumber = roadwayNumber+1),
        dummyProjectLink(RoadPart(1, 1), Track.LeftSide, Discontinuity.Continuous, AddrMRange(200L, 400L), Some(DateTime.now()), status = RoadAddressChangeType.Transfer,  administrativeClass = AdministrativeClass.State, roadwayNumber = roadwayNumber+2)
      )
      val newLink = dummyProjectLink(RoadPart(1, 1), Track.RightSide, Discontinuity.Continuous, AddrMRange(200L, 400L), Some(DateTime.now()), status = RoadAddressChangeType.New, administrativeClass = AdministrativeClass.State, roadwayNumber = roadwayNumber+3)

      val roadwayChanges = Seq(RoadwayFiller.RwChanges(roadway, Seq.empty[Roadway], projectLinks))
      val resultForUnchanged = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty)

      resultForUnchanged should have size 1

      val resultHead = resultForUnchanged.head
      resultHead._1 should have size 3
      resultHead._2 should have size 2
      resultHead._3 should have size 2

      resultHead._1.foreach(_.reversed should be(false))

      val createdRoadways = resultHead._1.filter(r => r.endDate.isEmpty && r.validTo.isEmpty)
      createdRoadways should have size 2
      val (rw1, rw2) = createdRoadways.partition(_.addrMRange.start == 0)

      rw1 should have size 1
      rw2 should have size 1

      rw1.head.discontinuity should be(projectLinks.head.discontinuity)
      rw1.head.roadwayNumber should be(projectLinks.head.roadwayNumber)
      rw1.head.addrMRange.end should be(projectLinks.head.addrMRange.end)
      rw1.head.track should be(projectLinks.head.track)

      rw2.head.discontinuity   should be(projectLinks(1).discontinuity)
      rw2.head.roadwayNumber   should be(projectLinks(1).roadwayNumber)
      rw2.head.addrMRange      should be(projectLinks(1).addrMRange)
      rw2.head.track           should be(projectLinks(1).track)

      val resultForNew = RoadwayFiller.applyNewLinks(Seq(newLink))
      resultForNew         should have size 1
      resultForNew.head._1 should have size 1

      resultForNew.head._1.foreach(_.reversed should be(false))

      val rw3 = resultForNew.head._1.head
      rw3.discontinuity   should be(newLink.discontinuity)
      rw3.roadwayNumber   should be(newLink.roadwayNumber)
      rw3.addrMRange      should be(newLink.addrMRange)
      rw3.track           should be(newLink.track)

      val historyRoadway = resultHead._1.filter(r => r.endDate.nonEmpty && r.validTo.isEmpty)
      historyRoadway should have size 1
      historyRoadway.head.discontinuity   should be(roadway.discontinuity)
      historyRoadway.head.roadwayNumber   should be(projectLinks.last.roadwayNumber)
      historyRoadway.head.addrMRange      should be(projectLinks.last.addrMRange)
    }
  }

  test("Test RoadwayFiller.applyRoadwayChanges() #Confluence: New Two Track Roadway with Minor Discontinuity on One Track." +
                 "When a new two track road with one minor discontinuity in the middle is created " +
                 "Then 4 new roadways should be created including one with minor discontinuity.") {
    runWithRollback{
      val roadwayNumber = 1L

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(), Seq(ProjectReservedPart(0L, RoadPart(1, 1), None, None, None, None, None, None, None)), Seq(), None)
      projectDAO.create(project)

      val newProjectLinks = Seq(
        dummyProjectLink(RoadPart(1, 1), Track.LeftSide,  Discontinuity.MinorDiscontinuity,AddrMRange(  0L, 200L), Some(DateTime.now()), status = RoadAddressChangeType.New, administrativeClass = AdministrativeClass.State,        roadwayNumber = roadwayNumber+1),
        dummyProjectLink(RoadPart(1, 1), Track.LeftSide,  Discontinuity.Continuous,        AddrMRange(200L, 400L), Some(DateTime.now()), status = RoadAddressChangeType.New, administrativeClass = AdministrativeClass.Municipality, roadwayNumber = roadwayNumber+2),
        dummyProjectLink(RoadPart(1, 1), Track.RightSide, Discontinuity.Continuous,        AddrMRange(  0L, 200L), Some(DateTime.now()), status = RoadAddressChangeType.New, administrativeClass = AdministrativeClass.State,        roadwayNumber = roadwayNumber+3),
        dummyProjectLink(RoadPart(1, 1), Track.RightSide, Discontinuity.Continuous,        AddrMRange(200L, 400L), Some(DateTime.now()), status = RoadAddressChangeType.New, administrativeClass = AdministrativeClass.Municipality, roadwayNumber = roadwayNumber+4)
      )

      val resultForNew = RoadwayFiller.applyNewLinks(newProjectLinks)
      resultForNew         should have size 4
      resultForNew.foreach(_._1 should have size 1)

      resultForNew.head._1.foreach(_.reversed should be(false))

      val (leftSide, rightSide) = resultForNew.partition(_._1.head.track == Track.LeftSide)
      leftSide  should have size 2
      rightSide should have size 2

      val (leftSide1, leftSide2) = leftSide.flatMap(_._1).partition(_.addrMRange.start == 0)
      leftSide1 should have size 1
      leftSide1.head.discontinuity       should be(newProjectLinks.head.discontinuity)
      leftSide1.head.roadwayNumber       should be(newProjectLinks.head.roadwayNumber)
      leftSide1.head.addrMRange          should be(newProjectLinks.head.addrMRange)
      leftSide1.head.track               should be(newProjectLinks.head.track)
      leftSide1.head.administrativeClass should be(newProjectLinks.head.administrativeClass)

      leftSide2.head.discontinuity       should be(newProjectLinks(1).discontinuity)
      leftSide2.head.roadwayNumber       should be(newProjectLinks(1).roadwayNumber)
      leftSide2.head.addrMRange          should be(newProjectLinks(1).addrMRange)
      leftSide2.head.track               should be(newProjectLinks(1).track)
      leftSide2.head.administrativeClass should be(newProjectLinks(1).administrativeClass)

      val (rightSide1, rightSide2) = rightSide.flatMap(_._1).partition(_.addrMRange.start == 0)
      rightSide1 should have size 1
      rightSide1.head.discontinuity       should be(newProjectLinks(2).discontinuity)
      rightSide1.head.roadwayNumber       should be(newProjectLinks(2).roadwayNumber)
      rightSide1.head.addrMRange          should be(newProjectLinks(2).addrMRange)
      rightSide1.head.track               should be(newProjectLinks(2).track)
      rightSide1.head.administrativeClass should be(newProjectLinks(2).administrativeClass)

      rightSide2.head.discontinuity       should be(newProjectLinks(3).discontinuity)
      rightSide2.head.roadwayNumber       should be(newProjectLinks(3).roadwayNumber)
      rightSide2.head.addrMRange          should be(newProjectLinks(3).addrMRange)
      rightSide2.head.track               should be(newProjectLinks(3).track)
      rightSide2.head.administrativeClass should be(newProjectLinks(3).administrativeClass)
    }
  }

  test("Test RoadwayFiller.applyRoadwayChanges() When dealing with unchanged at roads at the start and terminated at the end Then check correctly assigned roadway id's.") {
    runWithRollback {
      val roadways = Map(
        (0L, dummyRoadway(roadwayNumber = 1L, roadPart = RoadPart(1, 1), addrMRange = AddrMRange(0L, 200L), DateTime.now(), None))
      )

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(),
                                  Seq(ProjectReservedPart(0L, RoadPart(1, 1), None, None, None, None, None, None, None)),
                                  Seq(), None)
      projectDAO.create(project)

      val projectLinks = Seq(
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(  0L, 100L), Some(DateTime.now()), status = RoadAddressChangeType.Unchanged,   administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 10),
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(100L, 200L), Some(DateTime.now()), status = RoadAddressChangeType.Termination, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 20)
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

  test("Test RoadwayFiller.applyRoadwayChanges() When dealing with the termination of the first link and the transferring the remainder Then check correctly assigned roadway id's.") {
    runWithRollback {
      val roadways = Map(
        (0L, dummyRoadway(roadwayNumber = 1L, roadPart = RoadPart(1, 1), addrMRange = AddrMRange(0L, 200L), DateTime.now(), None))
      )

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(),
                                  Seq(ProjectReservedPart(0L, RoadPart(1, 1), None, None, None, None, None, None, None)),
                                  Seq(), None)
      projectDAO.create(project)

      val projectLinks = Seq(
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(  0L, 100L), Some(DateTime.now()), status = RoadAddressChangeType.Termination, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 10),
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(100L, 200L), Some(DateTime.now()), status = RoadAddressChangeType.Transfer,     administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 20)
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

  test("Test RoadwayFiller.applyRoadwayChanges() When dealing with a termination in the Middle of the Roadway Then check correctly assigned roadway id's.") {
    runWithRollback {
      val roadways = Map(
        (0L, dummyRoadway(roadwayNumber = 1L, roadPart = RoadPart(1, 1), addrMRange = AddrMRange(0L, 300L), DateTime.now(), None))
      )

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(),
                                  Seq(ProjectReservedPart(0L, RoadPart(1, 1), None, None, None, None, None, None, None)),
                                  Seq(), None)
      projectDAO.create(project)

      val projectLinks = Seq(
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(  0L, 100L), Some(DateTime.now()),                                 status = RoadAddressChangeType.Unchanged,   administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 10),
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(100L, 200L), Some(DateTime.now()), endDate = Some(DateTime.now()), status = RoadAddressChangeType.Termination, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 20),
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(100L, 500L), Some(DateTime.now()),                                 status = RoadAddressChangeType.New,         administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 30),
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(500L, 600L), Some(DateTime.now()),                                 status = RoadAddressChangeType.Transfer,    administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 40)
      )

      val roadwayChanges = roadways.values.map(r => RoadwayFiller.RwChanges(r, Seq.empty[Roadway], projectLinks.filterNot(_.status == RoadAddressChangeType.New))).toSeq
      val result2 = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty)
      val result3 = RoadwayFiller.applyNewLinks(projectLinks.filter(_.status == RoadAddressChangeType.New))
      val result = (result2.flatMap(_._1) ++ result3.flatMap(_._1)).groupBy(_.roadwayNumber).values.toSeq.sortBy(_.head.addrMRange.end)

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
  test("Test RoadwayFiller.applyRoadwayChanges()" +
    "When a single roadway (without history) is split to two roadways, but the first roadway has no changes" +
    "Then only the roadway with the changes will get a history row") {

    /**
      * BEFORE PROJECT
      *                                     Roadway Number 99
      *  0 |-------------------------------------RoadPart 1-----------------------------------------> 545   Current Roadway
      *
      * AFTER PROJECT
      *
      *                 Roadway Number 1                                    Roadway Number 2
      *  0 |----------------RoadPart 1------------------> 370    0 |---------RoadPart 2-------------> 175   New current roadways
      *
      *                                                        370 |---------RoadPart 1-------------> 545   After project created history roadway
      *
      * */

    runWithRollback{
      val roadwayNumber = 99L
      val newRoadwayNumber1 = 1L
      val newRoadwayNumber2 = 2L
      val roadway = dummyRoadway(roadwayNumber = roadwayNumber, roadPart = RoadPart(1, 1), addrMRange = AddrMRange(0L, 545L), DateTime.now().minusDays(2), None).copy(ely = 10)
      roadwayDAO.create(Seq(roadway))

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(), Seq(ProjectReservedPart(0L, RoadPart(1, 1), None, None, None, None, None, None, None)), Seq(), None)
      projectDAO.create(project)

      val projectLinks = Seq(
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange( 0L,  14L), Some(DateTime.now()), status = RoadAddressChangeType.Unchanged, administrativeClass = AdministrativeClass.State, roadwayNumber = newRoadwayNumber1).copy(originalAddrMRange = AddrMRange( 0L,  14L), ely = 10),
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(14L, 370L), Some(DateTime.now()), status = RoadAddressChangeType.Unchanged, administrativeClass = AdministrativeClass.State, roadwayNumber = newRoadwayNumber1).copy(originalAddrMRange = AddrMRange(14L, 370L), ely = 10),

        dummyProjectLink(RoadPart(1, 2), Track.Combined, Discontinuity.Continuous, AddrMRange(  0L, 120L), Some(DateTime.now()), status = RoadAddressChangeType.Transfer, administrativeClass = AdministrativeClass.State, roadwayNumber = newRoadwayNumber2).copy(originalAddrMRange = AddrMRange(370L, 490L), ely = 10),
        dummyProjectLink(RoadPart(1, 2), Track.Combined, Discontinuity.Continuous, AddrMRange(120L, 142L), Some(DateTime.now()), status = RoadAddressChangeType.Transfer, administrativeClass = AdministrativeClass.State, roadwayNumber = newRoadwayNumber2).copy(originalAddrMRange = AddrMRange(490L, 512L), ely = 10),
        dummyProjectLink(RoadPart(1, 2), Track.Combined, Discontinuity.EndOfRoad,  AddrMRange(142L, 175L), Some(DateTime.now()), status = RoadAddressChangeType.Transfer, administrativeClass = AdministrativeClass.State, roadwayNumber = newRoadwayNumber2).copy(originalAddrMRange = AddrMRange(512L, 545L), ely = 10)
      )

      val roadwayChanges = Seq(RoadwayFiller.RwChanges(roadway, Seq.empty[Roadway], projectLinks))
      val result         = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty)

      result should have size 1

      val resultRoadways = result.head._1

      val splitRoadways1 = resultRoadways.filter(rw => rw.roadwayNumber == newRoadwayNumber1)
      val splitRoadways2 = resultRoadways.filter(rw => rw.roadwayNumber == newRoadwayNumber2)

      splitRoadways1 should have size 1
      splitRoadways2 should have size 2

      val (newSplitRoadway1, historyRoadway1) = splitRoadways1.partition(rw => rw.endDate.isEmpty && rw.validTo.isEmpty)
      val (newSplitRoadway2, historyRoadway2) = splitRoadways2.partition(rw => rw.endDate.isEmpty && rw.validTo.isEmpty)

      newSplitRoadway1 should have size 1
      newSplitRoadway1.head.addrMRange should be (AddrMRange(0,370))
      newSplitRoadway1.head.roadPart should be (RoadPart(1, 1))

      historyRoadway1 should have size 0

      newSplitRoadway2 should have size 1
      newSplitRoadway2.head.addrMRange should be (AddrMRange(0,175))
      newSplitRoadway2.head.roadPart should be (RoadPart(1, 2))

      historyRoadway2 should have size 1
      historyRoadway2.head.addrMRange should be (AddrMRange(370,545))
      historyRoadway2.head.roadPart should be (RoadPart(1, 1))
    }
  }

  test("Test RoadwayFiller.applyRoadwayChanges()" +
    "When a single roadway (that has history) has a partial administrative class change at the start" +
    "Then new roadways and history roadways should have correct discontinuity") {

    /**
     * BEFORE PROJECT
     *
     *            RoadwayNumber 99 State AdminClass
     *     0 |--------------RoadPart 2----------------> 175  Current Roadway
     *
     *   370 |--------------RoadPart 1----------------> 545  History Roadway
     *
     * AFTER PROJECT
     *
     *        RoadwayNumber 100              RoadwayNumber 101
     *     0 |-----MUNICIPALITY------> 120                          New current roadway
     *                                     |---STATE---------> 175  Roadway that stays unchanged
     *        Discontinuity.Continuous     Discontinuity.EndOfRoad
     *     0 |-----STATE-------------> 120                          After project created history row
     *   370 |-----STATE-------------> 490 |---STATE---------> 545  Oldest history row
     * */

    runWithRollback {
      val roadwayNumber = 99L

      val roadway = dummyRoadway(roadwayNumber = roadwayNumber, roadPart = RoadPart(1, 2), addrMRange = AddrMRange(0L, 175L), DateTime.now().minusDays(5), None).copy(ely = 10, discontinuity = Discontinuity.EndOfRoad)
      val historyRoadway = dummyRoadway(roadwayNumber = roadwayNumber, roadPart = RoadPart(1, 1), addrMRange = AddrMRange(370L, 545L), DateTime.now().minusDays(10), Some(DateTime.now().minusDays(3)), 1L).copy(ely = 10, discontinuity = Discontinuity.EndOfRoad)
      roadwayDAO.create(Seq(roadway, historyRoadway))

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(), Seq(ProjectReservedPart(0L, RoadPart(1, 2), None, None, None, None, None, None, None)), Seq(), None)
      projectDAO.create(project)

      val projectLinks = Seq(
        dummyProjectLink(RoadPart(1, 2), Track.Combined, Discontinuity.Continuous, AddrMRange(  0L, 120L), Some(DateTime.now()), status = RoadAddressChangeType.Unchanged, administrativeClass = AdministrativeClass.Municipality, roadwayNumber = roadwayNumber + 1).copy(originalAddrMRange = AddrMRange(  0L, 120L), ely = 10),
        dummyProjectLink(RoadPart(1, 2), Track.Combined, Discontinuity.Continuous, AddrMRange(120L, 142L), Some(DateTime.now()), status = RoadAddressChangeType.Unchanged, administrativeClass = AdministrativeClass.State,        roadwayNumber = roadwayNumber + 2).copy(originalAddrMRange = AddrMRange(120L, 142L), ely = 10),
        dummyProjectLink(RoadPart(1, 2), Track.Combined, Discontinuity.EndOfRoad,  AddrMRange(142L, 175L), Some(DateTime.now()), status = RoadAddressChangeType.Unchanged, administrativeClass = AdministrativeClass.State,        roadwayNumber = roadwayNumber + 2).copy(originalAddrMRange = AddrMRange(142L, 175L), ely = 10)
      )

      val roadwayChanges = Seq(RoadwayFiller.RwChanges(roadway, Seq(historyRoadway), projectLinks))
      val result = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty)

      result should have size 1

      val (roadpartWithAdmChange, roadpartWithNoChange) = result.head._1.partition(rw => rw.roadwayNumber==100)

      val (newAdmChange, historyAdmChange) = roadpartWithAdmChange.partition(rw => rw.endDate.isEmpty && rw.validTo.isEmpty)

      newAdmChange should have size 1
      newAdmChange.head.discontinuity should be (Discontinuity.Continuous)

      historyAdmChange should have size 2
      historyAdmChange.head.discontinuity should be (Discontinuity.Continuous)
      historyAdmChange.last.discontinuity should be (Discontinuity.Continuous)

      val (newNoChange, historyNoChange) = roadpartWithNoChange.partition(rw => rw.endDate.isEmpty && rw.validTo.isEmpty)

      newNoChange should have size 1
      newNoChange.head.discontinuity should be (Discontinuity.EndOfRoad)

      historyNoChange should have size 1
      historyNoChange.head.discontinuity should be (Discontinuity.EndOfRoad)
    }
  }

  test("Test RoadwayFiller.applyRoadwayChanges()" +
    "When a roadway (that has history) is transferred to the start of another road address" +
    "Then the history roadways should have correct continuity and address M values") {

    /**
     * BEFORE PROJECT
     *
     *                   RoadwayNumber 99                      RoadwayNumber 100
     *      0 |-----RoadNumber 55 Part 1--------> 120  0 |---RoadNumber 56 Part 1----> 55  Current Roadway
     *
     *   370 |------RoadNumber 55 Part 1--------> 490  0 |---RoadNumber 56 Part 1----> 55  History Roadway
     *
     * AFTER PROJECT
     *
     *                   RoadwayNumber 99                      RoadwayNumber 100
     *             Discontinuity.Continuous
     *     0 |-----RoadNumber 56 Part 1---------> 120 120 |---RoadNumber 56 Part 1----> 175  New current roadway
     *             Discontinuity.EndOfRoad
     *     0 |------RoadNumber 55 Part 1--------> 120   0 |---RoadNumber 56 Part 1----> 55  After project created history row
     *   370 |------RoadNumber 55 Part 1--------> 490   0 |---RoadNumber 56 Part 1----> 55  Oldest history row
     * */

    runWithRollback {
      val roadwayNumber1 = 99L
      val roadwayNumber2 = 100L

      val roadway1        = dummyRoadway(roadwayNumber = roadwayNumber1, roadPart = RoadPart( 55, 1), addrMRange = AddrMRange(  0L, 120L), DateTime.now().minusDays(5), None).copy(ely = 10, discontinuity = Discontinuity.EndOfRoad)
      val historyRoadway1 = dummyRoadway(roadwayNumber = roadwayNumber1, roadPart = RoadPart( 55, 1), addrMRange = AddrMRange(370L, 490L), DateTime.now().minusDays(10), Some(DateTime.now().minusDays(3)), 1L).copy(ely = 10, discontinuity = Discontinuity.EndOfRoad)
      val roadway2        = dummyRoadway(roadwayNumber = roadwayNumber2, roadPart = RoadPart( 56, 1), addrMRange = AddrMRange(  0L,  55L), DateTime.now().minusDays(5), None).copy(ely = 10, discontinuity = Discontinuity.EndOfRoad, id = 52)
      val historyRoadway2 = dummyRoadway(roadwayNumber = roadwayNumber2, roadPart = RoadPart( 56, 1), addrMRange = AddrMRange(  0L,  55L), DateTime.now().minusDays(10), Some(DateTime.now().minusDays(3)), 1L).copy(ely = 10, discontinuity = Discontinuity.EndOfRoad, id = 53)
      roadwayDAO.create(Seq(roadway1, historyRoadway1, roadway2, historyRoadway2))

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(), Seq(ProjectReservedPart(0L, RoadPart(55, 1), None, None, None, None, None, None, None), ProjectReservedPart(0L, RoadPart(56, 1), None, None, None, None, None, None, None)), Seq(), None)
      projectDAO.create(project)

      val transferLink = Seq(dummyProjectLink(RoadPart(56, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(0L, 120L), Some(DateTime.now()), endDate = Some(DateTime.now()), status = RoadAddressChangeType.Transfer, administrativeClass = AdministrativeClass.State, roadwayNumber = roadwayNumber1).copy(originalAddrMRange = AddrMRange(0L, 120L), ely = 10))
      val projectLinks = Seq(
        dummyProjectLink(RoadPart(56, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(120L, 142L), Some(DateTime.now()), status = RoadAddressChangeType.Transfer, administrativeClass = AdministrativeClass.State, roadwayNumber = roadwayNumber2).copy(originalAddrMRange = AddrMRange( 0L, 22L), ely = 10),
        dummyProjectLink(RoadPart(56, 1), Track.Combined, Discontinuity.EndOfRoad,  AddrMRange(142L, 175L), Some(DateTime.now()), status = RoadAddressChangeType.Transfer, administrativeClass = AdministrativeClass.State, roadwayNumber = roadwayNumber2).copy(originalAddrMRange = AddrMRange(22L, 55L), ely = 10)
      )

      val roadwayChanges = Seq(RoadwayFiller.RwChanges(roadway1, Seq(historyRoadway1), transferLink),
        RoadwayFiller.RwChanges(roadway2, Seq(historyRoadway2), projectLinks))
      val result = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty)

      result should have size 2

      val roadway99 = result.head._1

      val (newRoadway99, historyRoadway99) = roadway99.partition(rw => rw.endDate.isEmpty && rw.validTo.isEmpty)

      newRoadway99 should have size 1
      newRoadway99.head.roadPart should be (RoadPart(56, 1))
      newRoadway99.head.discontinuity should be (Discontinuity.Continuous)
      newRoadway99.head.addrMRange    should be (AddrMRange(0,120))

      historyRoadway99.head.roadPart should be (RoadPart(55, 1))
      historyRoadway99.head.discontinuity should be (Discontinuity.EndOfRoad)
      historyRoadway99.head.addrMRange    should be (AddrMRange(0,120))

      historyRoadway99.last.roadPart should be (RoadPart(55, 1))
      historyRoadway99.last.discontinuity should be (Discontinuity.EndOfRoad)
      historyRoadway99.last.addrMRange    should be (AddrMRange(370,490))
    }
  }

  test("Test RoadwayFiller.applyRoadwayChanges()" +
    "When the end of a roadway that gets split (and that has history) is transferred to the start of another road address" +
    "Then the history roadways should be split correctly and have correct continuity and address M values") {

    /**
     * BEFORE PROJECT
     *
     *                RoadwayNumber 99                      RoadwayNumber 100
     *   0 |------RoadNumber 55 Part 1--------> 142  0 |---RoadNumber 56 Part 1----> 33  Current Roadway
     *
     * 370 |------RoadNumber 55 Part 1--------> 512  0 |---RoadNumber 56 Part 1----> 33  History Roadway
     *
     * AFTER PROJECT
     *
     *              RoadwayNumber 101                    RWN 102                 RoadwayNumber 100
     *           Discontinuity.EndOfRoad               Continuous
     *   0 |------RoadNumber 56 Part 1--------> 120   0 |----> 22  22 |---RoadNumber 56 Part 1----> 55  New current roadway
     *           Discontinuity.Continuous                 EOR
     *   0 |------RoadNumber 55 Part 1--------> 120 120 |----> 142  0 |---RoadNumber 56 Part 1----> 55  After project created history row
     * 370 |------RoadNumber 55 Part 1--------> 490 490 |----> 512  0 |---RoadNumber 56 Part 1----> 55  Oldest history row
     * */

    runWithRollback {
      val roadwayNumber1 = 99L
      val roadwayNumber2 = 100L

      val roadway1        = dummyRoadway(roadwayNumber = roadwayNumber1, roadPart = RoadPart(55, 1), addrMRange = AddrMRange(  0L, 142L), DateTime.now().minusDays(5), None).copy(ely = 10, discontinuity = Discontinuity.EndOfRoad, id=50)
      val historyRoadway1 = dummyRoadway(roadwayNumber = roadwayNumber1, roadPart = RoadPart(55, 1), addrMRange = AddrMRange(370L, 512L), DateTime.now().minusDays(10), Some(DateTime.now().minusDays(3)), 1L).copy(ely = 10, discontinuity = Discontinuity.EndOfRoad, id=51)
      val roadway2        = dummyRoadway(roadwayNumber = roadwayNumber2, roadPart = RoadPart(56, 1), addrMRange = AddrMRange(  0L,  33L), DateTime.now().minusDays(5), None).copy(ely = 10, discontinuity = Discontinuity.EndOfRoad, id = 52)
      val historyRoadway2 = dummyRoadway(roadwayNumber = roadwayNumber2, roadPart = RoadPart(56, 1), addrMRange = AddrMRange(  0L,  33L), DateTime.now().minusDays(10), Some(DateTime.now().minusDays(3)), 1L).copy(ely = 10, discontinuity = Discontinuity.EndOfRoad, id = 53)
      roadwayDAO.create(Seq(roadway1, historyRoadway1, roadway2, historyRoadway2))

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(), Seq(ProjectReservedPart(0L, RoadPart(55, 1), None, None, None, None, None, None, None), ProjectReservedPart(0L, RoadPart(56, 1), None, None, None, None, None, None, None)), Seq(), None)
      projectDAO.create(project)

      val transferRoadway = Seq(
        dummyProjectLink(RoadPart(55, 1), Track.Combined, Discontinuity.EndOfRoad,  AddrMRange(0L, 120L), Some(DateTime.now()), endDate = Some(DateTime.now()), status = RoadAddressChangeType.Transfer, administrativeClass = AdministrativeClass.State, roadwayNumber = roadwayNumber1+2).copy(originalAddrMRange = AddrMRange(0L, 120L), ely = 10, roadwayId=50),
        dummyProjectLink(RoadPart(56, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(0L,  22L), Some(DateTime.now()), status = RoadAddressChangeType.Transfer, administrativeClass = AdministrativeClass.State, roadwayNumber = roadwayNumber1+3).copy(originalAddrMRange = AddrMRange(120L, 142L), ely = 10, roadwayId=50)
      )
      val projectLinks = Seq(
        dummyProjectLink(RoadPart(56, 1), Track.Combined, Discontinuity.EndOfRoad, AddrMRange(22L, 55L), Some(DateTime.now()), status = RoadAddressChangeType.Transfer, administrativeClass = AdministrativeClass.State, roadwayNumber = roadwayNumber2).copy(originalAddrMRange = AddrMRange(0L, 33L), ely = 10, roadwayId=52)
      )

      val roadwayChanges = Seq(RoadwayFiller.RwChanges(roadway1, Seq(historyRoadway1), transferRoadway),
        RoadwayFiller.RwChanges(roadway2, Seq(historyRoadway2), projectLinks))
      val result = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty)

      result should have size 2

      val (roadway101, roadway102) = result.head._1.partition(rw => rw.roadwayNumber == 101)
      val (newRoadway101, historyRoadway101) = roadway101.partition(rw => rw.endDate.isEmpty && rw.validTo.isEmpty)

      newRoadway101 should have size 1
      newRoadway101.head.discontinuity should be (Discontinuity.EndOfRoad)
      newRoadway101.head.addrMRange.end should be (120L)

      historyRoadway101 should have size 2
      historyRoadway101.head.discontinuity should be (Discontinuity.Continuous)
      historyRoadway101.last.discontinuity should be (Discontinuity.Continuous)

      val (newRoadway102, historyRoadway102) = roadway102.partition(rw => rw.endDate.isEmpty && rw.validTo.isEmpty)

      newRoadway102 should have size 1
      newRoadway102.head.discontinuity should be (Discontinuity.Continuous)
      newRoadway102.head.addrMRange.end should be (22L)

      historyRoadway102 should have size 2
      historyRoadway102.head.discontinuity should be (Discontinuity.EndOfRoad)
      historyRoadway102.head.addrMRange.end should be (142L)
      historyRoadway102.last.discontinuity should be (Discontinuity.EndOfRoad)
      historyRoadway102.last.addrMRange.end should be (512L)

      val roadway103 = result.last._1
      val (newRoadway103, historyRoadway103) = roadway103.partition(rw => rw.endDate.isEmpty && rw.validTo.isEmpty)

      newRoadway103 should have size 1
      newRoadway103.head.discontinuity should be (Discontinuity.EndOfRoad)
      newRoadway103.head.addrMRange.end should be (55L)

      historyRoadway103 should have size 2
      historyRoadway103.head.discontinuity should be (Discontinuity.EndOfRoad)
      historyRoadway103.last.discontinuity should be (Discontinuity.EndOfRoad)
    }
  }

  test("Test RoadwayFiller.applyRoadwayChanges()" +
    "When a single roadway (that has history) has a partial termination at the start" +
    "Then new roadway and history roadways should have correct continuity and address M values") {

    /**
     * BEFORE PROJECT
     *
     *                   RoadwayNumber 99
     *     0 |--------------RoadPart 2----------------> 175  Current Roadway
     *
     *   370 |--------------RoadPart 1----------------> 545  History Roadway
     *
     * AFTER PROJECT
     *
     *                                       RoadwayNumber 100
     *                                   0 |---RoadPart 2----> 55  New current roadway
     *            RoadwayNumber 101
     *        Discontinuity.Continuous     Discontinuity.EndOfRoad
     *     0 |-----TERMINATED--------> 120 |---RoadPart 2----> 175  After project created history row
     *   370 |-----TERMINATED--------> 490 |---RoadPart 1----> 545  Oldest history row
     * */

    runWithRollback {
      val roadwayNumber = 99L

      val roadway        = dummyRoadway(roadwayNumber = roadwayNumber, roadPart = RoadPart(1, 2), addrMRange = AddrMRange(  0L, 175L), DateTime.now().minusDays(5), None).copy(ely = 10, discontinuity = Discontinuity.EndOfRoad)
      val historyRoadway = dummyRoadway(roadwayNumber = roadwayNumber, roadPart = RoadPart(1, 1), addrMRange = AddrMRange(370L, 545L), DateTime.now().minusDays(10), Some(DateTime.now().minusDays(3)), 1L).copy(ely = 10, discontinuity = Discontinuity.EndOfRoad)
      roadwayDAO.create(Seq(roadway, historyRoadway))

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(), Seq(ProjectReservedPart(0L, RoadPart(1, 2), None, None, None, None, None, None, None)), Seq(), None)
      projectDAO.create(project)

      val projectLinks = Seq(
        dummyProjectLink(RoadPart(1, 2), Track.Combined, Discontinuity.Continuous,AddrMRange( 0L, 120L), Some(DateTime.now()), endDate = Some(DateTime.now()), status = RoadAddressChangeType.Termination, administrativeClass = AdministrativeClass.State, roadwayNumber = roadwayNumber+1).copy(originalAddrMRange = AddrMRange(0L, 120L), ely = 10),
        dummyProjectLink(RoadPart(1, 2), Track.Combined, Discontinuity.Continuous,AddrMRange( 0L,  22L), Some(DateTime.now()), status = RoadAddressChangeType.Transfer, administrativeClass = AdministrativeClass.State, roadwayNumber = roadwayNumber+2).copy(originalAddrMRange = AddrMRange(120L, 142 ), ely = 10),
        dummyProjectLink(RoadPart(1, 2), Track.Combined, Discontinuity.EndOfRoad, AddrMRange(22L,  55L), Some(DateTime.now()), status = RoadAddressChangeType.Transfer, administrativeClass = AdministrativeClass.State, roadwayNumber = roadwayNumber+2).copy(originalAddrMRange = AddrMRange(142L, 175L), ely = 10)
      )

      val roadwayChanges = Seq(RoadwayFiller.RwChanges(roadway, Seq(historyRoadway), projectLinks))
      val result = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty)

      result should have size 2

      val resultRoadways = result.head._1
      val (newRoadway, historyRoadways) = resultRoadways.partition(rw => rw.endDate.isEmpty && rw.validTo.isEmpty)
      newRoadway should have size 1
      newRoadway.head.roadPart should be(RoadPart(1, 2))
      newRoadway.head.addrMRange should be(AddrMRange(0,55))

      historyRoadways should have size 2
      val newestHistoryRoadway = historyRoadways.head
      val oldestHistoryRoadway = historyRoadways.tail.head
      newestHistoryRoadway.discontinuity should be (Discontinuity.EndOfRoad)
      newestHistoryRoadway.roadPart should be (RoadPart(1, 2))
      newestHistoryRoadway.addrMRange should be (AddrMRange(120,175))
      oldestHistoryRoadway.discontinuity should be (Discontinuity.EndOfRoad)
      oldestHistoryRoadway.roadPart should be (RoadPart(1, 1))
      oldestHistoryRoadway.addrMRange should be (AddrMRange(490,545))

      val terminatedRoadways = result.last._1
      val (newTerminatedRoadway, historyTerminated) = terminatedRoadways.partition(rw => rw.endDate.isEmpty && rw.validTo.isEmpty)
      newTerminatedRoadway should have size 0
      historyTerminated should have size 2

      val newestTerminatedRoadway = historyTerminated.head
      val oldestTerminatedRoadway = historyTerminated.last
      newestTerminatedRoadway.discontinuity should be (Discontinuity.Continuous)
      newestTerminatedRoadway.roadPart should be (RoadPart(1, 2))
      newestTerminatedRoadway.addrMRange should be (AddrMRange(0,120))
      oldestTerminatedRoadway.discontinuity should be (Discontinuity.Continuous)
      oldestTerminatedRoadway.roadPart should be (RoadPart(1, 1))
      oldestTerminatedRoadway.addrMRange should be (AddrMRange(370,490))

    }
  }

  test("Test RoadwayFiller.applyRoadwayChanges()" +
    "When a single roadway (that has history) is transferred to another road part" +
    "Then new roadway and history roadways should form with correct address M values and road part numbers") {

    /**
      * BEFORE PROJECT
      *
      *                   RoadwayNumber 99
      *     0 |--------------RoadPart 2----------------> 175  Current Roadway
      *
      *   370 |--------------RoadPart 1----------------> 545  History Roadway
      *
      * AFTER PROJECT
      *
      *                   RoadwayNumber 99
      *     0 |--------------RoadPart 3----------------> 175  New current roadway
      *
      *     0 |--------------RoadPart 2----------------> 175  After project created history row
      *   370 |--------------RoadPart 1----------------> 545  Oldest history row
      * */

    runWithRollback{
      val roadwayNumber = 99L

      val roadway        = dummyRoadway(roadwayNumber = roadwayNumber, roadPart = RoadPart(1, 2), addrMRange = AddrMRange(  0L, 175L), DateTime.now().minusDays(5), None).copy(ely = 10)
      val historyRoadway = dummyRoadway(roadwayNumber = roadwayNumber, roadPart = RoadPart(1, 1), addrMRange = AddrMRange(370L, 545L), DateTime.now().minusDays(10), Some(DateTime.now().minusDays(3)), 1L).copy(ely = 10)
      roadwayDAO.create(Seq(roadway, historyRoadway))

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(), Seq(ProjectReservedPart(0L, RoadPart(1, 2), None, None, None, None, None, None, None)), Seq(), None)
      projectDAO.create(project)

      val projectLinks = Seq(
        dummyProjectLink(RoadPart(1, 3), Track.Combined, Discontinuity.Continuous, AddrMRange(  0L, 120L), Some(DateTime.now()), status = RoadAddressChangeType.Transfer, administrativeClass = AdministrativeClass.State, roadwayNumber = roadwayNumber).copy(originalAddrMRange = AddrMRange(  0L, 120L), ely = 10),
        dummyProjectLink(RoadPart(1, 3), Track.Combined, Discontinuity.Continuous, AddrMRange(120L, 142L), Some(DateTime.now()), status = RoadAddressChangeType.Transfer, administrativeClass = AdministrativeClass.State, roadwayNumber = roadwayNumber).copy(originalAddrMRange = AddrMRange(120L, 142 ), ely = 10),
        dummyProjectLink(RoadPart(1, 3), Track.Combined, Discontinuity.EndOfRoad,  AddrMRange(142L, 175L), Some(DateTime.now()), status = RoadAddressChangeType.Transfer, administrativeClass = AdministrativeClass.State, roadwayNumber = roadwayNumber).copy(originalAddrMRange = AddrMRange(142L, 175L), ely = 10)
      )

      val roadwayChanges = Seq(RoadwayFiller.RwChanges(roadway, Seq(historyRoadway), projectLinks))
      val result         = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty)

      result should have size 1

      val resultRoadways = result.head._1
      val (newRoadway, historyRoadways) = resultRoadways.partition(rw => rw.endDate.isEmpty && rw.validTo.isEmpty)
      newRoadway should have size 1
      newRoadway.head.roadPart should be (RoadPart(1, 3))
      newRoadway.head.addrMRange should be (AddrMRange(0,175))

      historyRoadways should have size 2
      val newestHistoryRoadway = historyRoadways.head
      val oldestHistoryRoadway = historyRoadways.tail.head
      newestHistoryRoadway.roadPart should be (RoadPart(1, 2))
      newestHistoryRoadway.addrMRange should be (AddrMRange(0,175))
      oldestHistoryRoadway.roadPart should be (RoadPart(1, 1))
      oldestHistoryRoadway.addrMRange should be (AddrMRange(370,545))
    }
  }

  test("Test RoadwayFiller.applyRoadwayChanges()" +
    "When a single roadway (that has two history rows) is split in to two roadways" +
    "Then the history roadways should also be split to those two new roadways," +
    "and new history roadway should be created for the roadway that had changes in the project." +
    "The history should be chronologically continuous on both roadways until termination") {

    /**
      * BEFORE PROJECT
      *
      *                   RoadwayNumber 99
      *     0 |--------------RoadPart 3----------------> 175  Current Roadway
      *
      *     0 |--------------RoadPart 2----------------> 175  Newest History Roadway
      *   370 |--------------RoadPart 1----------------> 545  Oldest History Roadway
      *
      *   AFTER PROJECT
      *
      *         RoadwayNumber 1         RoadwayNumber 2
      *     0 |------RP3----> 120     0 |-------RP4-----> 55  Current Roadways (rw 99 is split in two)
      *
      *                             120 |-------RP3-----> 175 After project created new history row for Rw2
      *     0 |------RP2----> 120   120 |-------RP2-----> 175 Rw1 newest history row and Rw2 second oldest history row
      *   370 |------RP1----> 490   490 |-------RP1-----> 545 Oldest history rows
      *
      *    For each non-terminated Roadway rw1 with an endDate, there exists a Roadway rw2 with the same roadwayNumber such that
      *    rw2's startDate is the day after rw1's endDate
      * */

    runWithRollback{
      val roadwayNumber = 99L
      val newRoadwayNumber1 = 1L
      val newRoadwayNumber2 = 2L

      val roadway = dummyRoadway(roadwayNumber = roadwayNumber, roadPart = RoadPart(1, 3), addrMRange = AddrMRange(0L, 175L), DateTime.now().minusDays(5), None, 1L).copy(ely = 10)
      val historyRoadways = Seq(
        dummyRoadway(roadwayNumber = roadwayNumber, roadPart = RoadPart(1, 2), addrMRange = AddrMRange(  0L, 175L), DateTime.now().minusDays(10), Some(DateTime.now().minusDays( 6)), 2L).copy(ely = 10),
        dummyRoadway(roadwayNumber = roadwayNumber, roadPart = RoadPart(1, 1), addrMRange = AddrMRange(370L, 545L), DateTime.now().minusDays(20), Some(DateTime.now().minusDays(11)), 3L).copy(ely = 10)
      )

      roadwayDAO.create(Seq(roadway) ++ historyRoadways)

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(), Seq(ProjectReservedPart(0L, RoadPart(1, 3), None, None, None, None, None, None, None)), Seq(), None)
      projectDAO.create(project)

      val projectLinks = Seq(
        dummyProjectLink(RoadPart(1, 3), Track.Combined, Discontinuity.Continuous,AddrMRange( 0L, 120L), Some(DateTime.now()), status = RoadAddressChangeType.Unchanged, administrativeClass = AdministrativeClass.State, roadwayNumber = newRoadwayNumber1).copy(originalAddrMRange = AddrMRange(  0L, 120L), ely = 10),
        dummyProjectLink(RoadPart(1, 4), Track.Combined, Discontinuity.Continuous,AddrMRange( 0L,  22L), Some(DateTime.now()), status = RoadAddressChangeType.Transfer,  administrativeClass = AdministrativeClass.State, roadwayNumber = newRoadwayNumber2).copy(originalAddrMRange = AddrMRange(120L, 142 ), ely = 10),
        dummyProjectLink(RoadPart(1, 4), Track.Combined, Discontinuity.EndOfRoad, AddrMRange(22L,  55L), Some(DateTime.now()), status = RoadAddressChangeType.Transfer,  administrativeClass = AdministrativeClass.State, roadwayNumber = newRoadwayNumber2).copy(originalAddrMRange = AddrMRange(142L, 175L), ely = 10)
      )

      val roadwayChanges = Seq(RoadwayFiller.RwChanges(roadway, historyRoadways, projectLinks))
      val result         = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty)

      result should have size 1
      val resultRoadways = result.head._1

      val (roadwaysForRoadPart3, roadwaysForRoadPart4) = resultRoadways.partition(rw => rw.roadwayNumber == 1)
      roadwaysForRoadPart3 should have size 3
      roadwaysForRoadPart4 should have size 4

      val (roadPart3newRoadway, roadPart3HistoryRows) = roadwaysForRoadPart3.partition(rw => rw.endDate.isEmpty && rw.validTo.isEmpty)
      roadPart3newRoadway should have size 1
      roadPart3HistoryRows should have size 2

      roadPart3newRoadway.head.roadPart should be (RoadPart(1, 3))
      roadPart3newRoadway.head.addrMRange should be (AddrMRange(0,120))

      roadPart3HistoryRows.head.roadPart should be (RoadPart(1, 2))
      roadPart3HistoryRows.head.addrMRange should be (AddrMRange(0,120))

      roadPart3HistoryRows.last.roadPart should be (RoadPart(1, 1))
      roadPart3HistoryRows.last.addrMRange should be (AddrMRange(370,490))

      val (roadPart4newRoadway, roadPart4HistoryRows) = roadwaysForRoadPart4.partition(rw => rw.endDate.isEmpty && rw.validTo.isEmpty)
      roadPart4newRoadway should have size 1
      roadPart4HistoryRows should have size 3

      roadPart4newRoadway.head.roadPart should be (RoadPart(1, 4))
      roadPart4newRoadway.head.addrMRange should be (AddrMRange(0,55))

      roadPart4HistoryRows.head.roadPart should be (RoadPart(1, 3))
      roadPart4HistoryRows.head.addrMRange should be (AddrMRange(120,175))

      roadPart4HistoryRows.tail.head.roadPart should be (RoadPart(1, 2))
      roadPart4HistoryRows.tail.head.addrMRange should be (AddrMRange(120,175))

      roadPart4HistoryRows.last.roadPart should be (RoadPart(1, 1))
      roadPart4HistoryRows.last.addrMRange should be (AddrMRange(490,545))


      //Check that there are no gaps in either roadway's history
      val pairedRoadwaysForRoadPart3 = roadwaysForRoadPart3.sortBy(_.startDate.getMillis).init.zip(roadwaysForRoadPart3.sortBy(_.startDate.getMillis).tail)
      pairedRoadwaysForRoadPart3.foreach{
        case (rw1, rw2) => rw1.endDate.get.plusDays(1).toLocalDate should equal (rw2.startDate.toLocalDate)
      }

      val pairedRoadwaysForRoadPart4 = roadwaysForRoadPart4.sortBy(_.startDate.getMillis).init.zip(roadwaysForRoadPart4.sortBy(_.startDate.getMillis).tail)
      pairedRoadwaysForRoadPart4.foreach{
        case (rw1, rw2) => rw1.endDate.get.plusDays(1).toLocalDate should equal (rw2.startDate.toLocalDate)
      }
    }
  }

  test("Test RoadwayFiller.applyRoadwayChanges() When dealing with a termination of a roadway with history Then check correctly assigned roadway id's.") {
    runWithRollback {
      val roadways = Map(
        (0L, dummyRoadway(roadwayNumber = 1L, roadPart = RoadPart(1, 1), addrMRange = AddrMRange(0L, 200L), DateTime.parse("1950-01-01"), None))
      )

      val historyRoadways = Map(
        (0L, dummyRoadway(roadwayNumber = 1L, roadPart = RoadPart(1, 1), addrMRange = AddrMRange(100L, 300L), DateTime.parse("1901-01-01"), Some(DateTime.parse("1950-01-01"))))
      )

      val projectLinks = Seq(
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(  0L, 100L), Some(DateTime.now()), status = RoadAddressChangeType.Termination, administrativeClass = AdministrativeClass.apply(1)),
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(100L, 200L), Some(DateTime.now()), status = RoadAddressChangeType.Termination, administrativeClass = AdministrativeClass.apply(1))
      )

      val roadwayChanges = roadways.values.map(r => RoadwayFiller.RwChanges(r, historyRoadways.values.toSeq, projectLinks.filterNot(_.status == RoadAddressChangeType.New))).toSeq
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

  test("Test RoadwayFiller.applyRoadwayChanges() When dealing with transferred addresses check if the end_addr_m values are correct"){
    runWithRollback {
      val roadways = Map(
        (0L, dummyRoadway(roadwayNumber = 1L, roadPart = RoadPart(9999, 1), addrMRange = AddrMRange(0L,  400L), DateTime.now(), None)),
        (0L, dummyRoadway(roadwayNumber = 1L, roadPart = RoadPart(9999, 2), addrMRange = AddrMRange(0L, 1000L), DateTime.now(), None))
      )

      val project = dummyProject(UpdatingToRoadNetwork, DateTime.now(), DateTime.now(), DateTime.now(),
                                  Seq(ProjectReservedPart(0L, RoadPart(9999, 1), None, None, None, None, None, None, None),
                                      ProjectReservedPart(0L, RoadPart(9999, 2), None, None, None, None, None, None, None)),
                                  Seq(), None)
      projectDAO.create(project)

      val projectLinks = Seq(
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(  0L,  100L), Some(DateTime.now()), status = RoadAddressChangeType.Unchanged, administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 10),
        dummyProjectLink(RoadPart(1, 2), Track.Combined, Discontinuity.Continuous, AddrMRange(  0L,  300L), Some(DateTime.now()), status = RoadAddressChangeType.Transfer,  administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 20),
        dummyProjectLink(RoadPart(1, 2), Track.Combined, Discontinuity.Continuous, AddrMRange(300L, 1000L), Some(DateTime.now()), status = RoadAddressChangeType.Transfer,  administrativeClass = AdministrativeClass.apply(1), roadwayNumber = 30)
      )

      val roadwayChanges = roadways.values.map(r => RoadwayFiller.RwChanges(r, Seq.empty[Roadway], projectLinks.filterNot(_.status == RoadAddressChangeType.New))).toSeq
      val result2 = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty).head._1.sortBy(r=> (r.addrMRange.start,r.roadPart))
      val result = result2.groupBy(_.roadwayNumber).values.toSeq.sortBy(rwseq => rwseq.minBy(_.addrMRange.start).addrMRange.end).toList

      result.size should be(3)
      result.head.head.roadwayNumber should not be roadways.head._2.roadwayNumber
      result.head.head.roadPart should be (RoadPart(1, 1))
      result.head.head.addrMRange.end should be(100)
      result(1).size should be(2)
      result(1).last.addrMRange should be (AddrMRange(  0, 300))
      result(2).size should be(2)
      result(2).last.addrMRange should be (AddrMRange(300,1000))
    }
  }

  test("Test RoadwayFiller.applyRoadwayChanges() When dealing with Unchanged + Transfer with same properties and same roadwayNumber then they should be merged into one"){
    runWithRollback {
      val roadwayNumber1 = 1

      val roadways = Map(
        (0L, dummyRoadway(roadwayNumber = 1L, roadPart = RoadPart(1, 1), addrMRange = AddrMRange(0L, 300L), DateTime.now(), None))
      )

      val projectLinks = Seq(
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(  0L, 100L), Some(DateTime.now()), status = RoadAddressChangeType.Unchanged, administrativeClass = AdministrativeClass.apply(1)).copy(ely = 0, roadwayNumber = roadwayNumber1),
        dummyProjectLink(RoadPart(1, 1), Track.Combined, Discontinuity.Continuous, AddrMRange(100L, 300L), Some(DateTime.now()), status = RoadAddressChangeType.Transfer,  administrativeClass = AdministrativeClass.apply(1)).copy(ely = 0, roadwayNumber = roadwayNumber1)
      )

      val roadwayChanges = roadways.values.map(r => RoadwayFiller.RwChanges(r, Seq.empty[Roadway], projectLinks.filterNot(_.status == RoadAddressChangeType.New))).toSeq
      val result = RoadwayFiller.applyRoadwayChanges(roadwayChanges).flatten.filter(_._1.nonEmpty).head._1.sortBy(r=> (r.addrMRange.start,r.roadPart))

      result.size should be(1)
    }
  }

}
