package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.asset.LifecycleStatus.UnknownLifecycleStatus
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.NormalLinkInterface
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.viite.dao._
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}


class RoadAddressFillerSpec extends FunSuite with Matchers with BeforeAndAfter {

  private def dummyLinearLocation(id: Long, roadwayNumber: Long, orderNumber: Long, linkId: String, startMValue: Double, endMValue: Double, yCoordinates: Seq[Double]): LinearLocation =
    dummyLinearLocation(id, roadwayNumber, orderNumber, linkId, startMValue, endMValue, yCoordinates, LinkGeomSource.NormalLinkInterface)

  private def dummyLinearLocation(id: Long, roadwayNumber: Long, orderNumber: Long, linkId: String, startMValue: Double, endMValue: Double, yCoordinates: Seq[Double], linkGeomSource: LinkGeomSource): LinearLocation = {
    LinearLocation(id, orderNumber, linkId, startMValue, endMValue, SideCode.TowardsDigitizing, 0L, (CalibrationPointReference.None, CalibrationPointReference.None), yCoordinates.map(y => Point(0.0, y)), linkGeomSource, roadwayNumber)
  }

  private def dummyRoadLink(linkId: String, yCoordinates: Seq[Double], linkGeomSource: LinkGeomSource): RoadLink = {
    RoadLink(linkId, yCoordinates.map(y => Point(0.0, y)), yCoordinates.sum - yCoordinates.head, AdministrativeClass.Municipality, TrafficDirection.TowardsDigitizing, None, None, Map(), UnknownLifecycleStatus, linkGeomSource, 257)
  }

  test("Test adjustToTopology When there is any exists a linear location to be adjusted Then should not have any change set") {
    val linearLocations = Seq(
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0, Seq(0.0, 10.0)),
      dummyLinearLocation(id = 2L, roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0, Seq(10.0, 20.0)),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0, Seq(20.0, 30.0)),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0, Seq(30.0, 40.0))
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L.toString, Seq(0.0, 10.0, 20.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L.toString, Seq(20.0, 30.0), NormalLinkInterface),
      dummyRoadLink(linkId = 125L.toString, Seq(30.0, 40.0), NormalLinkInterface)
    )

    val (adjustedLinearLocations, changeSet) = RoadAddressFiller.adjustToTopology(roadLinks, linearLocations)

    adjustedLinearLocations should have size 4
    changeSet.adjustedMValues should have size 0
    changeSet.droppedSegmentIds should have size 0
  }

  test("Test adjustToTopology When exists a linear location outside road link geometry Then linear location should be dropped") {
    val linearLocations = Seq(
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0, Seq(0.0, 10.0)),
      dummyLinearLocation(id = 2L, roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0, Seq(10.0, 20.0)),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0, Seq(20.0, 30.0)),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0, Seq(30.0, 40.0)),
      dummyLinearLocation(id = 5L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 10.0, endMValue = 20.0, Seq(30.0, 40.0))
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L.toString, Seq(0.0, 10.0, 20.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L.toString, Seq(20.0, 30.0), NormalLinkInterface),
      dummyRoadLink(linkId = 125L.toString, Seq(30.0, 40.0), NormalLinkInterface)
    )

    val (adjustedLinearLocations, changeSet) = RoadAddressFiller.adjustToTopology(roadLinks, linearLocations)

    adjustedLinearLocations should have size 4
    adjustedLinearLocations.map(_.id) should contain allOf(1L, 2L, 3L, 4L)

    changeSet.droppedSegmentIds should have size 1
    changeSet.droppedSegmentIds.head should be(5L)
    changeSet.adjustedMValues should have size 0
  }

  test("Test adjustToTopology When exists a linear location with 1 meter longer than road link geometry Then linear location should be cap to road link geometry") {
    val linearLocations = Seq(
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0, Seq(0.0, 10.0)),
      dummyLinearLocation(id = 2L, roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0, Seq(10.0, 20.0)),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 11.001, Seq(20.0, 30.0)),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 11.0, Seq(30.0, 41.0))
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L.toString, Seq(0.0, 10.0, 20.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L.toString, Seq(20.0, 30.0), NormalLinkInterface),
      dummyRoadLink(linkId = 125L.toString, Seq(30.0, 35.0, 40.0), NormalLinkInterface)
    )

    val (adjustedLinearLocations, changeSet) = RoadAddressFiller.adjustToTopology(roadLinks, linearLocations)

    adjustedLinearLocations should have size 4
    val aLinearLocation = adjustedLinearLocations.find(_.id == 4L).get
    aLinearLocation.startMValue should be(0.0)
    aLinearLocation.endMValue should be(10.0)
    aLinearLocation.geometry should have size 2
    aLinearLocation.geometry should be(Seq(Point(0.0, 30), Point(0.0, 40)))

    changeSet.droppedSegmentIds should have size 0
    changeSet.droppedSegmentIds should have size 0
    changeSet.adjustedMValues should have size 1
    val adjustedMValue = changeSet.adjustedMValues.head
    adjustedMValue.geometry should have size 2
    adjustedMValue.geometry should be(Seq(Point(0.0, 30), Point(0.0, 40)))
    adjustedMValue.linearLocationId should be(4L)
    adjustedMValue.linkId should be(125L.toString)
    adjustedMValue.startMeasure should be(None)
    adjustedMValue.endMeasure should be(Some(10.0))
  }

  test("Test adjustToTopology When exists a linear location with 1 meter shorter than road link geometry Then linear location should be extended to road link geometry") {
    val linearLocations = Seq(
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0, Seq(0.0, 10.0)),
      dummyLinearLocation(id = 2L, roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0, Seq(10.0, 20.0)),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 8.99, Seq(20.0, 29.99)),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 9.0, Seq(30.0, 39.0))
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L.toString, Seq(0.0, 10.0, 20.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L.toString, Seq(20.0, 30.0), NormalLinkInterface),
      dummyRoadLink(linkId = 125L.toString, Seq(30.0, 35.0, 40.0), NormalLinkInterface)
    )

    val (adjustedLinearLocations, changeSet) = RoadAddressFiller.adjustToTopology(roadLinks, linearLocations)

    adjustedLinearLocations should have size 4
    val aLinearLocation = adjustedLinearLocations.find(_.id == 4L).get
    aLinearLocation.startMValue should be(0.0)
    aLinearLocation.endMValue should be(10.0)
    aLinearLocation.geometry should have size 2
    aLinearLocation.geometry should be(Seq(Point(0.0, 30), Point(0.0, 40)))

    changeSet.droppedSegmentIds should have size 0
    changeSet.droppedSegmentIds should have size 0
    changeSet.adjustedMValues should have size 1
    val adjustedMValue = changeSet.adjustedMValues.head
    adjustedMValue.geometry should have size 2
    adjustedMValue.geometry should be(Seq(Point(0.0, 30), Point(0.0, 40)))
    adjustedMValue.linearLocationId should be(4L)
    adjustedMValue.linkId should be(125L.toString)
    adjustedMValue.startMeasure should be(None)
    adjustedMValue.endMeasure should be(Some(10.0))
  }

  test("Test adjustToTopology When exists a linear location with adjustments and one linear location floating in same road link Then any adjustment should be applied") {
    val linearLocations = Seq(
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0, Seq(0.0, 10.0)),
      dummyLinearLocation(id = 2L, roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 21.0, Seq(10.0, 21.0)),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0, Seq(20.0, 30.0)),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0, Seq(30.0, 40.0))
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L.toString, Seq(0.0, 10.0, 20.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L.toString, Seq(20.0, 30.0), NormalLinkInterface),
      dummyRoadLink(linkId = 125L.toString, Seq(30.0, 35.0, 40.0), NormalLinkInterface)
    )

    val (adjustedLinearLocations, changeSet) = RoadAddressFiller.adjustToTopology(roadLinks, linearLocations)

    adjustedLinearLocations should have size 4
    changeSet.adjustedMValues should have size 1
    changeSet.droppedSegmentIds should have size 0
  }

}
