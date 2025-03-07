package fi.liikennevirasto.viite

import fi.liikennevirasto.viite.dao._
import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC

class APIServiceForNodesAndJunctions(roadwayDAO: RoadwayDAO, linearLocationDAO: LinearLocationDAO, nodeDAO: NodeDAO, junctionDAO: JunctionDAO) {

  def runWithReadOnlySession[T](f: => T): T = PostGISDatabaseScalikeJDBC.runWithReadOnlySession(f)

  private def getAllValidNodes: Seq[Node] = {
    runWithReadOnlySession {
      nodeDAO.fetchAllValidNodes()
    }
  }

  private def getJunctionsWithLinearLocation(validNodeNumbers: Seq[Long]): Seq[JunctionWithLinearLocation] = {
    runWithReadOnlySession {
      junctionDAO.fetchJunctionsByNodeNumbersWithLinearLocation(validNodeNumbers)
    }
  }

  private def getCrossingRoads: Seq[RoadwaysForJunction] = {
    runWithReadOnlySession {
      roadwayDAO.fetchCrossingRoadsInJunction()
    }
  }

  private def getCurrentLinearLocations: Seq[LinearLocation] = {
    runWithReadOnlySession {
      linearLocationDAO.fetchCurrentLinearLocations
    }
  }

  private def getCoordinatesForJunction(llIds: Seq[Long], crossingRoads: Seq[RoadwaysForJunction], currentLinearLocations: Seq[LinearLocation]): Option[Point] = {
    runWithReadOnlySession {
      linearLocationDAO.fetchCoordinatesForJunction(llIds, crossingRoads, currentLinearLocations)
    }
  }


  /**
   * 1. Gets all valid nodes, junctions, linear locations, junctions with linear location id (for connecting purposes), all roads connected to a junction (separated based on junction)
   * 2. Map junctions to nodes
   * 3. Map crossing roads to junctions
   * 4. getCoordinatesToJunction (based on junction's linear locations, crossingRoads are passed on for 2-road edge-case purposes)
   * 5. Map coordinates to junctions
   *
   * @return All valid nodes including:
   *         Junctions in that node
   *         Roads connected to that junction
   *         Coordinates of the junction (the blue circle)
   *         Example: Seq(Node(Junction1(Road1, Road2),Junction2(Road3,Road4))
   */
  def getAllValidNodesWithJunctions: Seq[NodeWithJunctions] = {
    // Get all required data to keep the query duration short
    // The data is retrieved with separate functions (e.g. getAllValidNodes) to keep the Dynamic Sessions separate and to avoid nested Dynamic Sessions
    val nodes: Seq[Node] = getAllValidNodes
    val validNodeNumbers: Seq[Long] = nodes.map(node => node.nodeNumber)
    val junctions: Seq[JunctionWithLinearLocation] = getJunctionsWithLinearLocation(validNodeNumbers)
    val currentLinearLocations = getCurrentLinearLocations
    val allCrossingRoads = getCrossingRoads
println(s"getAllValidNodesWithJunctions got ${nodes.size} nodes, ${validNodeNumbers.size} validNodeNumbers, " +
  s"${junctions.size} junctions, ${currentLinearLocations.size} currentLinearLocations, and ${allCrossingRoads.size} allCrossingRoads.") // TODO remove when debugging is done. This is ugly!

    // 1. Junctions are mapped with calculated coordinate and crossing roads
    // 2. Nodes are mapped with junctions (which include coordinates and crossing roads)
    // 3. List of all valid nodes and junctions is returned
    val nodesWithJunctions: Seq[NodeWithJunctions] = nodes.map(node => {
      val junctionsWithCoordinates: Seq[JunctionWithCoordinateAndCrossingRoads] = junctions.collect {
        case j if j.nodeNumber.contains(node.nodeNumber) =>
          val crossingRoads: Seq[RoadwaysForJunction] = allCrossingRoads.filter(cr => cr.jId == j.id)
          val coordinates: Option[Point] = getCoordinatesForJunction(j.llId, crossingRoads, currentLinearLocations)
          val (x, y) = coordinates match {
            case Some(c) => (c.x, c.y)
            case None => (0.0, 0.0) // If junction's coordinates are not found, return Point(0.0, 0.0) which is later printed as "N/A" in the API
          }
          JunctionWithCoordinateAndCrossingRoads(j.id, j.junctionNumber, j.nodeNumber, j.startDate, j.endDate, j.validFrom, j.validTo, j.createdBy, j.createdTime, x, y, crossingRoads)
      }
      NodeWithJunctions(node, junctionsWithCoordinates)
    })
    nodesWithJunctions
  }
}
