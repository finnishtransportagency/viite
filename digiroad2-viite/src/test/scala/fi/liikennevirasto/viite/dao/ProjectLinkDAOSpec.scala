package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.{DigiroadEventBus, Point}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import org.joda.time.DateTime
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

/**
  * Class to test DB trigger that does not allow reserving already reserved links to project
  */
class ProjectLinkDAOSpec extends FunSuite with Matchers {
  val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockEventBus = MockitoSugar.mock[DigiroadEventBus]
  val mockRoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]
  val mockLinearLocationDAO = MockitoSugar.mock[LinearLocationDAO]
  val mockRoadwayDAO = MockitoSugar.mock[RoadwayDAO]
  val mockRoadNetworkDAO = MockitoSugar.mock[RoadNetworkDAO]

  def runWithRollback(f: => Unit): Unit = {
    // Prevent deadlocks in DB because we create and delete links in tests and don't handle the project ids properly
    // TODO: create projects with unique ids so we don't get into transaction deadlocks in tests
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }
  val projectLinkDAO = new ProjectLinkDAO

  //TODO will be implement at VIITE-1539
//  //TODO: this test is always deadlocking, need to check better
//  test("check the removal of project links") {
//    val address = ReservedRoadPart(5: Long, 5: Long, 203: Long, Some(6L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
//    runWithRollback {
//      val id = Sequences.nextViitePrimaryKeySeqValue
//      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List(address), None)
//      ProjectDAO.createRoadAddressProject(rap)
//      ProjectDAO.reserveRoadPart(id, address.roadNumber, address.roadPartNumber, rap.createdBy)
//      val addresses = RoadAddressDAO.fetchByRoadPart(5, 203).map(toProjectLink(rap))
//      ProjectDAO.create(addresses)
//      ProjectDAO.getRoadAddressProjectById(id).nonEmpty should be(true)
//      val projectLinks = ProjectDAO.getProjectLinks(id)
//      ProjectDAO.removeProjectLinksById(projectLinks.map(_.id).toSet) should be(projectLinks.size)
//      ProjectDAO.getProjectLinks(id).nonEmpty should be(false)
//    }
//  }

  //TODO will be implement at VIITE-1540
//  // Tests now also for VIITE-855 - accidentally removing all links if set is empty
//  test("Update project link status and remove zero links") {
//    runWithRollback {
//      val id = Sequences.nextViitePrimaryKeySeqValue
//      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
//      ProjectDAO.createRoadAddressProject(rap)
//      ProjectDAO.reserveRoadPart(id, 5, 203, rap.createdBy)
//      val addresses = RoadAddressDAO.fetchByRoadPart(5, 203).map(toProjectLink(rap))
//      ProjectDAO.create(addresses)
//      val projectLinks = ProjectDAO.getProjectLinks(id)
//      ProjectDAO.updateProjectLinks(projectLinks.map(x => x.id).toSet, LinkStatus.Terminated, "test")
//      val updatedProjectLinks = ProjectDAO.getProjectLinks(id)
//      updatedProjectLinks.head.status should be(LinkStatus.Terminated)
//      ProjectDAO.removeProjectLinksByLinkId(id, Set())
//      ProjectDAO.getProjectLinks(id).size should be(updatedProjectLinks.size)
//    }
//  }

  //TODO will be implement at VIITE-1540
//  test("Update project link to reversed") {
//    runWithRollback {
//      val id = Sequences.nextViitePrimaryKeySeqValue
//      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
//      ProjectDAO.createRoadAddressProject(rap)
//      ProjectDAO.reserveRoadPart(id, 5, 203, rap.createdBy)
//      val addresses = RoadAddressDAO.fetchByRoadPart(5, 203).map(toProjectLink(rap))
//      ProjectDAO.create(addresses)
//      val projectLinks = ProjectDAO.getProjectLinks(id)
//      projectLinks.count(x => !x.reversed) should be(projectLinks.size)
//      val maxM = projectLinks.map(_.endAddrMValue).max
//      val reversedprojectLinks = projectLinks.map(x => x.copy(reversed = true, status=Transfer,
//        startAddrMValue = maxM-x.endAddrMValue, endAddrMValue = maxM-x.startAddrMValue))
//      ProjectDAO.updateProjectLinksToDB(reversedprojectLinks, "testuset")
//      val updatedProjectLinks = ProjectDAO.getProjectLinks(id)
//      updatedProjectLinks.count(x => x.reversed) should be(updatedProjectLinks.size)
//    }
//  }

  //TODO will be implement at VIITE-1539
//  test("Create reversed project link") {
//    runWithRollback {
//      val id = Sequences.nextViitePrimaryKeySeqValue
//      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
//      ProjectDAO.createRoadAddressProject(rap)
//      ProjectDAO.reserveRoadPart(id, 5, 203, rap.createdBy)
//      val addresses = RoadAddressDAO.fetchByRoadPart(5, 203).map(toProjectLink(rap))
//      ProjectDAO.create(addresses.map(x => x.copy(reversed = true)))
//      val projectLinks = ProjectDAO.getProjectLinks(id)
//      projectLinks.count(x => x.reversed) should be(projectLinks.size)
//    }
//  }

//TODO Will be implemented at VIITE-1540
//  test("update project link") {
//    runWithRollback {
//      val projectLinks = ProjectDAO.getProjectLinks(7081807)
//      ProjectDAO.updateProjectLinks(projectLinks.map(x => x.id).toSet, LinkStatus.UnChanged, "test")
//      val savedProjectLinks = ProjectDAO.getProjectLinks(7081807)
//      ProjectDAO.updateProjectLinksToDB(Seq(savedProjectLinks.sortBy(_.startAddrMValue).last.copy(status = LinkStatus.Terminated)), "tester")
//      val terminatedLink = projectLinks.sortBy(_.startAddrMValue).last
//      val updatedProjectLinks = ProjectDAO.getProjectLinks(7081807).filter(link => link.id == terminatedLink.id)
//      val updatedLink = updatedProjectLinks.head
//      updatedLink.status should be(LinkStatus.Terminated)
//      updatedLink.discontinuity should be(Discontinuity.Continuous)
//      updatedLink.startAddrMValue should be(savedProjectLinks.sortBy(_.startAddrMValue).last.startAddrMValue)
//      updatedLink.endAddrMValue should be(savedProjectLinks.sortBy(_.startAddrMValue).last.endAddrMValue)
//      updatedLink.track should be(savedProjectLinks.sortBy(_.startAddrMValue).last.track)
//      updatedLink.roadType should be(savedProjectLinks.sortBy(_.startAddrMValue).last.roadType)
//    }
//  }

  test("Empty list will not throw an exception") {
    projectLinkDAO.getProjectLinksByIds(Seq())
    projectLinkDAO.removeProjectLinksById(Set())
  }

  //TODO will be implement at VIITE-1540
//  test("Change road address direction") {
//    runWithRollback {
//      val id = Sequences.nextViitePrimaryKeySeqValue
//      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
//      ProjectDAO.createRoadAddressProject(rap)
//      ProjectDAO.reserveRoadPart(id, 5, 203, rap.createdBy)
//      val addresses = RoadAddressDAO.fetchByRoadPart(5, 203).map(toProjectLink(rap))
//      ProjectDAO.create(addresses)
//      val (psidecode, linkid) = sql"select SIDE, ID from PROJECT_LINK where PROJECT_LINK.PROJECT_ID = $id".as[(Long, Long)].first
//      psidecode should be(2)
//      ProjectDAO.reverseRoadPartDirection(id, 5, 203)
//      val nsidecode = sql"select SIDE from PROJECT_LINK WHERE id=$linkid".as[Int].first
//      nsidecode should be(3)
//      ProjectDAO.reverseRoadPartDirection(id, 5, 203)
//      val bsidecode = sql"select SIDE from PROJECT_LINK WHERE id=$linkid".as[Int].first
//      bsidecode should be(2)
//    }
//  }

  test("update project_link's road_type and discontinuity") {
    runWithRollback {
      val projectLinks = projectLinkDAO.getProjectLinks(7081807)
      val biggestProjectLink = projectLinks.maxBy(_.endAddrMValue)
      projectLinkDAO.updateProjectLinkRoadTypeDiscontinuity(projectLinks.map(x => x.id).filterNot(_ == biggestProjectLink.id).toSet, LinkStatus.UnChanged, "test", 2, None)
      projectLinkDAO.updateProjectLinkRoadTypeDiscontinuity(Set(biggestProjectLink.id), LinkStatus.UnChanged, "test", 2, Some(2))
      val savedProjectLinks = projectLinkDAO.getProjectLinks(7081807)
      savedProjectLinks.filter(_.roadType.value == 2).size should be(savedProjectLinks.size)
      savedProjectLinks.filter(_.discontinuity.value == 2).size should be(1)
      savedProjectLinks.filter(_.discontinuity.value == 2).head.id should be(biggestProjectLink.id)
    }
  }

  //TODO will be implement at VIITE-1540
//  test("update ProjectLinkgeom") {
//    //Creation of Test road
//    val address = ReservedRoadPart(5: Long, 203: Long, 203: Long, Some(6L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
//    runWithRollback {
//      val id = Sequences.nextViitePrimaryKeySeqValue
//      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List(address), None)
//      ProjectDAO.createRoadAddressProject(rap)
//      val addresses = RoadAddressDAO.fetchByRoadPart(5, 203).map(toProjectLink(rap))
//      ProjectDAO.reserveRoadPart(id, 5, 203, "TestUser")
//      ProjectDAO.create(addresses)
//      ProjectDAO.getRoadAddressProjectById(id).nonEmpty should be(true)
//      val projectlinksWithDummyGeom = ProjectDAO.getProjectLinks(id).map(x=>x.copy(geometry = Seq(Point(1,1,1),Point(2,2,2),Point(1,2))))
//      ProjectDAO.updateProjectLinksGeometry(projectlinksWithDummyGeom,"test")
//      val updatedProjectlinks=ProjectDAO.getProjectLinks(id)
//      updatedProjectlinks.head.geometry.size should be (3)
//      updatedProjectlinks.head.geometry should be (Stream(Point(1.0,1.0,1.0), Point(2.0,2.0,2.0), Point(1.0,2.0,0.0)))
//    }
//  }

}