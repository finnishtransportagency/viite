package fi.liikennevirasto.viite

import fi.liikennevirasto.viite.dao._
import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.model.{LinkGeomSource, NodeType, RoadPart, SideCode}
import fi.vaylavirasto.viite.postgis.PostGISDatabase.runWithRollback
import org.joda.time.DateTime
import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.BeforeAndAfter
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers


class APIServiceForNodesAndJunctionsSpec extends AnyFunSuite with Matchers with BeforeAndAfter {

  private val mockLinearLocationDAO = MockitoSugar.mock[LinearLocationDAO]
  private val mockRoadwayDAO = MockitoSugar.mock[RoadwayDAO]
  private val mockJunctionDAO = MockitoSugar.mock[JunctionDAO]
  private val mockNodeDAO = MockitoSugar.mock[NodeDAO]

  val APIServiceForNodesAndJunctions: APIServiceForNodesAndJunctions =
    new APIServiceForNodesAndJunctions(
      mockRoadwayDAO,
      mockLinearLocationDAO,
      mockNodeDAO,
      mockJunctionDAO
    )     {
      override def withDynSession[T](f: => T): T = f
    }

  test("Test getAllValidNodesWithJunctions When valid nodes and junctions exist Then return valid nodes with only the junctions that are connected to the node and the junction's coordinate pointing to the junction's location (blue circle on the map), leaving the not connected junctions excluded") {
      runWithRollback {
        // test data
        val nodeNumber = 1
        val nodes: Seq[Node] = Seq(Node(NewIdValue, nodeNumber, Point(100, 100), Some("Test node"), NodeType.NormalIntersection,
          DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, "Test", None, registrationDate = new DateTime()))
        when(mockNodeDAO.fetchAllValidNodes()).thenReturn(nodes)

        val validNodeNumbers: Seq[Long] = nodes.map(node => node.nodeNumber)
        val llId1 = 1
        val llId2 = 2
        val llId3 = 3
        val llIds: Seq[Long] = Seq(llId1, llId2, llId3)

        // MOCK DATA
        // First junction is created to be included in the result and the second is a dummy that should be excluded from the result
        val junctions: Seq[JunctionWithLinearLocation] = Seq(JunctionWithLinearLocation(1, None, Some(nodeNumber), DateTime.now(), None, DateTime.now(), None, "TestUser", None, Seq(llId1, llId2, llId3)), JunctionWithLinearLocation(2, None, Some(nodeNumber+1), DateTime.now(), None, DateTime.now(), None, "TestUser", None, Seq()))
        when(mockJunctionDAO.fetchJunctionsByNodeNumbersWithLinearLocation(validNodeNumbers)).thenReturn(junctions)

        // Linear locations all share 1 same coordinate point (101.0, 101.0) where all the linear locations connect (junction's coordinates)
        val allLL: Seq[LinearLocation] = Seq(LinearLocation(llId1, 1, 1000L.toString, 0.0, 2.8, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(99.0, 99.0), Point(101.0, 101.0)), LinkGeomSource.NormalLinkInterface, -1), LinearLocation(llId2, 1, 1001L.toString, 2.8, 6.8, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(101.0, 101.0), Point(105, 105.0)), LinkGeomSource.NormalLinkInterface, -1), LinearLocation(llId3, 1, 1002L.toString, 0, 4, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(96.0, 100.0), Point(101, 101.0)), LinkGeomSource.NormalLinkInterface, -1))
        when(mockLinearLocationDAO.fetchCurrentLinearLocations).thenReturn(allLL)

        // Dummy roads to me mocked (not important in this test but have to be set)
        val allCrossingRoads: Seq[RoadwaysForJunction] = Seq(RoadwaysForJunction(1, 1, RoadPart(1, 0), 1, 3, 1), RoadwaysForJunction(1, 2, RoadPart(1, 0), 1, 7, 2), RoadwaysForJunction(1, 3, RoadPart(2, 0), 1, 0, 2))
        when(mockRoadwayDAO.fetchCrossingRoadsInJunction()).thenReturn(allCrossingRoads)

        val junctionCoordinate: Option[Point] = Some(Point(101.0, 101.0))
        when(mockLinearLocationDAO.fetchCoordinatesForJunction(llIds, allCrossingRoads, allLL)).thenReturn(junctionCoordinate)

        //TESTED FUNCTION
        val nodesWithJunctions: Seq[NodeWithJunctions] = APIServiceForNodesAndJunctions.getAllValidNodesWithJunctions

        //RESULT
        nodesWithJunctions.size should be(1)
        // Most important test:
        nodesWithJunctions.head.junctionsWithCoordinates.size should be(1)

        nodesWithJunctions.head.junctionsWithCoordinates.head.xCoord should be(101.0)
        nodesWithJunctions.head.junctionsWithCoordinates.head.yCoord should be(101.0)
      }
    }
}
