package fi.liikennevirasto.viite

import fi.liikennevirasto.viite.dao._
import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.postgis.PostGISDatabase

class APIServiceForNodesAndJunctions(roadwayDAO: RoadwayDAO, linearLocationDAO: LinearLocationDAO, nodeDAO: NodeDAO, junctionDAO: JunctionDAO) {

  def withDynSession[T](f: => T): T = PostGISDatabase.withDynSession(f)

  private def getNodes: Seq[Node] = {
    withDynSession {
      nodeDAO.fetchAllValidNodes()
    }
  }

  private def getJunctionsWithLinearLocation(validNodeNumbers: Seq[Long]): Seq[JunctionWithLinearLocation] = {
    withDynSession {
      junctionDAO.fetchJunctionsByNodeNumbersWithLinearLocation(validNodeNumbers)
    }
  }

  private def getCrossingRoads: Seq[RoadwaysForJunction] = {
    withDynSession {
      roadwayDAO.fetchCrossingRoadsInJunction()
    }
  }

  private def getCurrentLinearLocations: Seq[LinearLocation] = {
    withDynSession {
      linearLocationDAO.fetchCurrentLinearLocations
    }
  }

  private def getCoordinatesForJunction(llIds: Seq[Long], crossingRoads: Seq[RoadwaysForJunction], currentLinearLocations: Seq[LinearLocation]): Option[Point] = {
    withDynSession {
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
   *         Example: Seq(Node(Junction1(Road1, Road2),Junction2(Road3,Road4))
   */
  def getAllValidNodesWithJunctions: Seq[NodeWithJunctions] = {
    val nodes: Seq[Node] = getNodes
    val validNodeNumbers: Seq[Long] = nodes.map(node => node.nodeNumber)
    val junctions: Seq[JunctionWithLinearLocation] = getJunctionsWithLinearLocation(validNodeNumbers)
    val currentLinearLocations = getCurrentLinearLocations
    val allCrossingRoads = getCrossingRoads

    val nodesWithJunctions: Seq[NodeWithJunctions] = nodes.map(node => {
      val junctionsWithCoordinates: Seq[JunctionWithCoordinateAndCrossingRoads] = junctions.collect {
        case j if j.nodeNumber.contains(node.nodeNumber) =>
          val crossingRoads: Seq[RoadwaysForJunction] = allCrossingRoads.filter(cr => cr.jId == j.id)
          val coordinates: Option[Point] = getCoordinatesForJunction(j.llId, crossingRoads, currentLinearLocations)
          val (x, y) = coordinates match {
            case Some(c) => (c.x, c.y)
            case None => (0.0, 0.0)
          }
          JunctionWithCoordinateAndCrossingRoads(j.id, j.junctionNumber, j.nodeNumber, j.startDate, j.endDate, j.validFrom, j.validTo, j.createdBy, j.createdTime, x, y, crossingRoads)
      }
      NodeWithJunctions(node, junctionsWithCoordinates)
    })
    nodesWithJunctions
  }
}