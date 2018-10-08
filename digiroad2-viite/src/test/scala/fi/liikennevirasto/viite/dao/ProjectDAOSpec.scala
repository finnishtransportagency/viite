package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.LinkStatus.Transfer
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

/**
  * Class to test DB trigger that does not allow reserving already reserved links to project
  */
class ProjectDAOSpec extends FunSuite with Matchers {

  def runWithRollback(f: => Unit): Unit = {
    // Prevent deadlocks in DB because we create and delete links in tests and don't handle the project ids properly
    // TODO: create projects with unique ids so we don't get into transaction deadlocks in tests
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }

  test("create empty road address project") {
    runWithRollback {
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
      ProjectDAO.createRoadAddressProject(rap)
      ProjectDAO.getRoadAddressProjectById(id).nonEmpty should be(true)
    }
  }

  test("open empty project") {
    runWithRollback {
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
      ProjectDAO.createRoadAddressProject(rap)
      ProjectDAO.getRoadAddressProjectById(id).nonEmpty should be(true)
      val projectLinks = ProjectLinkDAO.getProjectLinksByIds(id,Set())
      projectLinks.isEmpty should be (true)
    }
  }

//TODO will be implement at VIITE-1539
//  test("create road address project with project links and verify if the geometry is set") {
//    val address = ReservedRoadPart(5: Long, 203: Long, 203: Long, Some(6L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
//    runWithRollback {
//      val id = Sequences.nextViitePrimaryKeySeqValue
//      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List(address), None)
//      ProjectDAO.createRoadAddressProject(rap)
//      val addresses = RoadAddressDAO.fetchByRoadPart(5, 203).map(toProjectLink(rap))
//      ProjectDAO.reserveRoadPart(id, 5, 203, "TestUser")
//      ProjectDAO.create(addresses)
//      ProjectDAO.getRoadAddressProjectById(id).nonEmpty should be(true)
//      val projectlinks = ProjectDAO.getProjectLinks(id)
//      projectlinks.length should be > 0
//      projectlinks.forall(_.status == LinkStatus.NotHandled) should be(true)
//      projectlinks.forall(_.geometry.size == 2) should be (true)
//      projectlinks.sortBy(_.endAddrMValue).map(_.geometry).zip(addresses.sortBy(_.endAddrMValue).map(_.geometry)).forall {case (x, y) => x == y}
//      ProjectDAO.fetchFirstLink(id, 5, 203) should be(Some(projectlinks.minBy(_.startAddrMValue)))
//    }
//  }

  test("get projects waiting TR response") {
    val address = ProjectReservedPart(5: Long, 203: Long, 203: Long, Some(6L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
    runWithRollback {
      val waitingCountp = ProjectDAO.getProjectsWithWaitingTRStatus().length
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap = RoadAddressProject(id, ProjectState.apply(2), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List(address), None)
      ProjectDAO.createRoadAddressProject(rap)
      val waitingCountNow = ProjectDAO.getProjectsWithWaitingTRStatus().length
      waitingCountNow - waitingCountp should be(1)
    }
  }

  //TODO will be implement at VIITE-1539
//  test("Create project with no reversed links") {
//    runWithRollback {
//      val id = Sequences.nextViitePrimaryKeySeqValue
//      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
//      ProjectDAO.createRoadAddressProject(rap)
//      ProjectDAO.reserveRoadPart(id, 5, 203, rap.createdBy)
//      val addresses = RoadAddressDAO.fetchByRoadPart(5, 203).map(toProjectLink(rap))
//      ProjectDAO.create(addresses.map(x => x.copy(reversed = false)))
//      val projectLinks = ProjectDAO.getProjectLinks(id)
//      projectLinks.count(x => x.reversed) should be(0)
//    }
//  }

  test("Update project status") {
    runWithRollback {
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
      ProjectDAO.createRoadAddressProject(rap)
      ProjectDAO.updateProjectStatus(id, ProjectState.Saved2TR)
      ProjectDAO.getProjectStatus(id) should be(Some(ProjectState.Saved2TR))
    }
  }

  test("Update project info") {
    val address = ProjectReservedPart(5: Long, 203: Long, 203: Long, Some(6L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
    runWithRollback {
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List(address), None)
      val updatedrap = RoadAddressProject(id, ProjectState.apply(1), "newname", "TestUser", DateTime.parse("1901-01-02"), "TestUser", DateTime.parse("1901-01-02"), DateTime.now(), "updated info", List(address), None)
      ProjectDAO.createRoadAddressProject(rap)
      ProjectDAO.updateRoadAddressProject(updatedrap)
      ProjectDAO.getRoadAddressProjectById(id) match {
        case Some(project) =>
          project.name should be("newname")
          project.additionalInfo should be("updated info")
          project.startDate should be(DateTime.parse("1901-01-02"))
          project.dateModified.getMillis should be > DateTime.parse("1901-01-03").getMillis + 100000000
        case None => None should be(RoadAddressProject)
      }
    }
  }

  test("get roadaddressprojects") {
    runWithRollback {
      val projectListSize = ProjectDAO.getRoadAddressProjects().length
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty[ProjectReservedPart], None)
      ProjectDAO.createRoadAddressProject(rap)
      val projectList = ProjectDAO.getRoadAddressProjects()
      projectList.length - projectListSize should be(1)
    }
  }

  //TODO Will be implemented at VIITE-1539
//  test("change project Ely DAO") {
//    runWithRollback {
//      val id = Sequences.nextViitePrimaryKeySeqValue
//      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
//      ProjectDAO.createRoadAddressProject(rap)
//      ProjectDAO.getRoadAddressProjectById(id).nonEmpty should be(true)
//      ProjectDAO.updateProjectEly(id, 100)
//      ProjectDAO.getProjectEly(id).get should be(100)
//    }
//  }

  test("Get Projects whose status is SendintToTR") {
    val address = ProjectReservedPart(5: Long, 203: Long, 203: Long, Some(6L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
    runWithRollback {
      val waitingCountp = ProjectDAO.getProjectsWithSendingToTRStatus().length
      val id = Sequences.nextViitePrimaryKeySeqValue
      val rap = RoadAddressProject(id, ProjectState.apply(9), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List(address), None)
      ProjectDAO.createRoadAddressProject(rap)
      val waitingCountNow = ProjectDAO.getProjectsWithSendingToTRStatus().length
      waitingCountNow - waitingCountp should be(1)
    }
  }
}