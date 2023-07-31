package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.viite.NewIdValue
import fi.vaylavirasto.viite.dao.Sequences
import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.model.{AdministrativeClass, BeforeAfter, Discontinuity, NodePointType, NodeType, Track}
import fi.vaylavirasto.viite.postgis.PostGISDatabase.runWithRollback
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}

class JunctionDAOSpec extends FunSuite with Matchers {

  val dao = new JunctionDAO
  val nodeDAO = new NodeDAO
  val roadwayDAO = new RoadwayDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val nodePointDAO = new NodePointDAO
  val junctionPointDAO = new JunctionPointDAO

  private val testJunction1 = Junction(NewIdValue, None, None, DateTime.parse("2019-01-01"), None,
    DateTime.parse("2019-01-01"), None, "Test", None)

  private val testJunction2 = Junction(NewIdValue, None, None, DateTime.parse("2019-01-02"), None,
    DateTime.parse("2019-01-02"), None, "Test", None)

  private val testNode1 = Node(NewIdValue, NewIdValue, Point(100, 100), Some("Test node 1"), NodeType.NormalIntersection,
    DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, "Test", None, registrationDate = new DateTime())

  test("Test create When nothing to create Then return empty Seq") {
    runWithRollback {
      val ids = dao.create(Seq())
      ids.isEmpty should be(true)
    }
  }

  test("Test create When one created Then return Seq with one id") {
    runWithRollback {
      val ids = dao.create(Seq(testJunction1))
      ids.size should be(1)
    }
  }

  test("Test create When two created Then return Seq with two ids") {
    runWithRollback {
      val ids = dao.create(Seq(testJunction1, testJunction2))
      ids.size should be(2)
    }
  }

  test("Test expireById When two templates created and one expired Then expire one and keep the other") {
    runWithRollback {
      val ids = dao.create(Seq(testJunction1, testJunction2))
      dao.expireById(Seq(ids.head))
      val fetched = dao.fetchByIds(ids)
      fetched.size should be(1)
      fetched.head.id should be(ids.last)
    }
  }

  test("Test expireById When two created and one expired Then expire one and keep the other") {
    runWithRollback {
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      val ids = dao.create(Seq(testJunction1.copy(nodeNumber = Some(nodeNumber)), testJunction2.copy(nodeNumber = Some(nodeNumber))))
      dao.expireById(Seq(ids.head))
      val fetched = dao.fetchByIds(ids)
      fetched.size should be(1)
      fetched.head.id should be(ids.last)
    }
  }

  test("Test fetchJunctionByNodeIds When non-existing node Then return none") {
    runWithRollback {
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      dao.create(Seq(testJunction1.copy(nodeNumber = Some(nodeNumber)), testJunction2.copy(nodeNumber = Some(nodeNumber))))
      val fetched = dao.fetchJunctionsByNodeNumbers(Seq(nodeNumber + 1)) // Non-existing node id
      fetched.size should be(0)
    }
  }

  test("Test fetchJunctionByNodeIds When fetched Then return junctions") {
    runWithRollback {
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      val fetched1 = dao.fetchJunctionsByNodeNumbers(Seq(nodeNumber))
      fetched1.size should be(0)
      dao.create(Seq(testJunction1.copy(nodeNumber = Some(nodeNumber)), testJunction2.copy(nodeNumber = Some(nodeNumber))))
      val fetched2 = dao.fetchJunctionsByNodeNumbers(Seq(nodeNumber))
      fetched2.size should be(2)
    }
  }

  test("Test When fetching junctions for road address browser then return junctions based on the query") {

    /**
      *
      *           RW1                   RW2
      * |-----------------X---->--------------->
      * 0                250  300             500   ADDRESS M
      *
      *  X = Junction
      *
      * */

    runWithRollback {
      val roadNumber = 76
      val roadPartNumber = 1
      val junctionAddrM = 250
      val endAddrM = 500

      // create nodes
      val nodeNumber1 = Sequences.nextNodeNumber
      val nodeNumber2 = Sequences.nextNodeNumber
      nodeDAO.create(
        Seq(
          Node(Sequences.nextNodeId, nodeNumber1, Point(100, 100) ,Some("TestNode"), NodeType.NormalIntersection, DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, "test", Some(DateTime.parse("2019-01-01")), None, Some(DateTime.parse("2019-01-01")), DateTime.parse("2019-01-01")),
          Node(Sequences.nextNodeId, nodeNumber2, Point(100, 200) ,Some("TestNode"), NodeType.EndOfRoad, DateTime.parse("2021-01-01"), None, DateTime.parse("2021-01-01"), None, "test", Some(DateTime.parse("2021-01-01")), None, Some(DateTime.parse("2021-01-01")), DateTime.parse("2021-01-01"))
        )
      )
      // create roadways
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      roadwayDAO.create(
        Seq(
          Roadway(roadwayDAO.getNextRoadwayId, roadwayNumber1, roadNumber, roadPartNumber, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0, 300, reversed = false, DateTime.parse("1992-10-08"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination),
          Roadway(roadwayDAO.getNextRoadwayId, roadwayNumber2, roadNumber, roadPartNumber, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 300, 500, reversed = false, DateTime.parse("2021-01-01"), None, "test", Some("TEST ROAD 1"), 8, TerminationCode.NoTermination)

        )
      )
      // create roadway points
      val rwpId = roadwayPointDAO.create(
        RoadwayPoint(Sequences.nextRoadwayPointId, roadwayNumber1, junctionAddrM, "test", Some(DateTime.parse("1992-10-08")), None, None)
      )
      val rwpId2 = roadwayPointDAO.create(
        RoadwayPoint(Sequences.nextRoadwayPointId, roadwayNumber2, endAddrM, "test", Some(DateTime.parse("2021-01-01")), None, None)
      )
      // create node points
      nodePointDAO.create(
        Seq(
          NodePoint(Sequences.nextNodePointId, BeforeAfter.Before, rwpId, Some(nodeNumber1), NodePointType.RoadNodePoint, Some(DateTime.parse("1992-10-08")), None, DateTime.parse("1992-10-08"), None, "test", Some(DateTime.parse("1992-10-08")), roadwayNumber1, junctionAddrM, roadNumber, roadPartNumber, Track.Combined, 1, Point(100, 100)),
          NodePoint(Sequences.nextNodePointId, BeforeAfter.Before, rwpId2, Some(nodeNumber2), NodePointType.RoadNodePoint, Some(DateTime.parse("2021-01-01")), None, DateTime.parse("2021-01-01"), None, "test", Some(DateTime.parse("2021-01-01")), roadwayNumber2, endAddrM, roadNumber, roadPartNumber, Track.Combined, 1, Point(100, 200))
        )
      )
      // create junction
      val junctionId = Sequences.nextJunctionId
      dao.create(
        Seq(
          Junction(junctionId, Some(1), Some(nodeNumber1), DateTime.parse("1992-10-08"), None, DateTime.parse("1992-10-08"), None, "test", Some(DateTime.parse("1992-10-08")), None)
        )
      )
      // create junction points
      junctionPointDAO.create(
        Seq(
          JunctionPoint(Sequences.nextJunctionPointId, BeforeAfter.Before, rwpId, junctionId, Some(DateTime.parse("1992-10-08")), None, DateTime.parse("1992-10-08"), None, "test", Some(DateTime.parse("1992-10-08")), roadwayNumber1, junctionAddrM, roadNumber, roadPartNumber, Track.Combined, Discontinuity.Continuous, Point(100,100)),
          JunctionPoint(Sequences.nextJunctionPointId, BeforeAfter.After,  rwpId, junctionId, Some(DateTime.parse("1992-10-08")), None, DateTime.parse("1992-10-08"), None, "test", Some(DateTime.parse("1992-10-08")), roadwayNumber1, junctionAddrM, roadNumber, roadPartNumber, Track.Combined, Discontinuity.Continuous, Point(100,100))
        )
      )

      // fetch and test
      val resultForQuery1 = dao.fetchJunctionsForRoadAddressBrowser(Some("2022-01-01"), None, Some(roadNumber), None, None)
      resultForQuery1.size should be (1)
      resultForQuery1.head shouldBe a [JunctionForRoadAddressBrowser]
      resultForQuery1.head.beforeAfter should (contain (1) and contain (2) and have length 2) // 1 = Before, 2 = After

      val resultForQuery2 = dao.fetchJunctionsForRoadAddressBrowser(Some("1992-01-01"), None, Some(roadNumber), None, None)
      resultForQuery2.size should be (0)
    }
  }

}
