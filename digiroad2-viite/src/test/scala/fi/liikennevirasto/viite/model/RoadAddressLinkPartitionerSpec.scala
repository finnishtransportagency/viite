package fi.liikennevirasto.viite.model

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.ConstructionType.InUse
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.{ComplimentaryLinkInterface, NormalLinkInterface, SuravageLinkInterface}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite.RoadType
import fi.liikennevirasto.viite.RoadType.PublicRoad
// Used in debugging when needed.
import org.scalatest.{FunSuite, Matchers}

/**
  * Created by venholat on 21.10.2016.
  */
class RoadAddressLinkPartitionerSpec extends FunSuite with Matchers {
  lazy val roadAddressLinks = Seq(
    makeRoadAddressLink(1, 0, 1, 1),
    makeRoadAddressLink(2, 0, 1, 1),
    makeRoadAddressLink(3, 0, 1, 1),
    makeRoadAddressLink(4, 0, 1, 1),
    makeRoadAddressLink(5, 0, 1, 1),
    makeRoadAddressLink(6, 0, 1, 1),
    makeRoadAddressLink(7, 0, 1, 1),
    makeRoadAddressLink(11, 0, 1, 2),
    makeRoadAddressLink(12, 0, 1, 2),
    makeRoadAddressLink(13, 0, 1, 2),
    makeRoadAddressLink(14, 0, 1, 2),
    makeRoadAddressLink(15, 0, 1, 2),
    makeRoadAddressLink(16, 0, 1, 2),
    makeRoadAddressLink(17, 0, 1, 2),
    makeRoadAddressLink(0, 1, 0, 0, 1.0, 1.0),
    makeRoadAddressLink(0, 1, 0, 0, 11.0, 1.0),
    makeRoadAddressLink(0, 1, 0, 0, 21.0, 1.0),
    makeRoadAddressLink(0, 1, 0, 0, 31.0, 1.0),
    makeRoadAddressLink(0, 1, 0, 0, 41.0, 1.0),
    makeRoadAddressLink(0, 1, 0, 0, 1.0, 11.0),
    makeRoadAddressLink(0, 1, 0, 0, 11.0, 11.0),
    makeRoadAddressLink(0, 1, 0, 0, 21.0, 11.0),
    makeRoadAddressLink(0, 1, 0, 0, 31.0, 11.0),
    makeRoadAddressLink(0, 1, 0, 0, 41.0, 11.0)
  )

  private def makeRoadAddressLink(id: Long, anomaly: Int, roadNumber: Long, roadPartNumber: Long, deltaX: Double = 0.0, deltaY: Double = 0.0) = {
    RoadAddressLink(id, id, id, Seq(Point(id * 10.0 + deltaX, anomaly * 10.0 + deltaY), Point((id + 1) * 10.0 + deltaX, anomaly * 10.0 + deltaY)), 10.0, State, SingleCarriageway,  InUse, NormalLinkInterface, PublicRoad, Some("Vt5"), None, BigInt(0), None, None, Map(), roadNumber, roadPartNumber, 1, 1, 1, id * 10, (id + 1) * 10, "", "", 0.0, 10.0, SideCode.TowardsDigitizing, None, None, Anomaly.apply(anomaly))
  }

  test("Test partition should have specific fields (still to be defined) not empty") {

    val roadLinks = Seq(RoadAddressLink(0, 0, 5171208, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)),
      0.0, Municipality, UnknownLinkType, InUse, NormalLinkInterface, RoadType.MunicipalityStreetRoad,
      Some("Vt5"), None, BigInt(0), None, None, Map("linkId" -> 5171208, "segmentId" -> 63298), 5, 205, 1, 0, 0, 0, 1, "2015-01-01", "2016-01-01",
      0.0, 0.0, SideCode.Unknown, None, None, Anomaly.None, 123))

    val partitionedRoadLinks = RoadAddressLinkPartitioner.partition(roadLinks)

    val roadPartNumber = partitionedRoadLinks.head.head.roadPartNumber
    val roadNumber = partitionedRoadLinks.head.head.roadNumber
    val trackCode = partitionedRoadLinks.head.head.trackCode
    val segmentId = partitionedRoadLinks.head.head.id
    val constructionType = partitionedRoadLinks.head.head.constructionType.value
    val roadwayNumber = partitionedRoadLinks.head.head.roadwayNumber

    segmentId should not be None
    roadNumber should be(5)
    roadPartNumber should be(205)
    trackCode should be(1)
    constructionType should be(0)
    roadwayNumber should be(123)

  }

  test("Test partition When 1 link have Anomaly.NoAddressGiven and other one have any other Anomaly Then they will be in different groups of RoadAddressLinks") {

    val roadLinks = Seq(RoadAddressLink(0, 0, 5171208, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)),
      0.0, Municipality, UnknownLinkType, InUse, NormalLinkInterface, RoadType.MunicipalityStreetRoad,
      Some("Vt5"), None, BigInt(0), None, None, Map("linkId" -> 5171208, "segmentId" -> 63298), 1, 205, 1, 0, 0, 0, 1, "2015-01-01", "2016-01-01",
      0.0, 0.0, SideCode.Unknown, None, None, Anomaly.NoAddressGiven, 123),
      RoadAddressLink(0, 0, 5171208, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)),
      0.0, Municipality, UnknownLinkType, InUse, NormalLinkInterface, RoadType.MunicipalityStreetRoad,
      Some("Vt5"), None, BigInt(0), None, None, Map("linkId" -> 5171208, "segmentId" -> 63298), 1, 205, 1, 0, 0, 0, 1, "2015-01-01", "2016-01-01",
      0.0, 0.0, SideCode.Unknown, None, None, Anomaly.None, 123)
    )

    val partitionedRoadLinks = RoadAddressLinkPartitioner.partition(roadLinks)
    partitionedRoadLinks.size should be (2)
  }

  test("Test partition When 2 links have at least different ROADNUMBER Then they will be in different groups of RoadAddressLinks") {
    val roadNumber1 = 1
    val roadNumber2 = 2

    val roadLinks = Seq(RoadAddressLink(0, 0, 5171208, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)),
      0.0, Municipality, UnknownLinkType, InUse, NormalLinkInterface, RoadType.MunicipalityStreetRoad,
      Some("Vt5"), None, BigInt(0), None, None, Map("linkId" -> 5171208, "segmentId" -> 63298), roadNumber1, 205, 1, 0, 0, 0, 1, "2015-01-01", "2016-01-01",
      0.0, 0.0, SideCode.Unknown, None, None, Anomaly.None, 123),
      RoadAddressLink(0, 0, 5171208, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)),
      0.0, Municipality, UnknownLinkType, InUse, NormalLinkInterface, RoadType.MunicipalityStreetRoad,
      Some("Vt5"), None, BigInt(0), None, None, Map("linkId" -> 5171208, "segmentId" -> 63298), roadNumber2, 205, 1, 0, 0, 0, 1, "2015-01-01", "2016-01-01",
      0.0, 0.0, SideCode.Unknown, None, None, Anomaly.None, 123)
    )

    val partitionedRoadLinks = RoadAddressLinkPartitioner.partition(roadLinks)
    partitionedRoadLinks.size should be (2)
  }

  test("Test partition When 2 links have at least different ROADPARTNUMBER Then they will be in different groups of RoadAddressLinks") {
    val roadPartNumber1 = 1
    val roadPartNumber2 = 2

    val roadLinks = Seq(RoadAddressLink(0, 0, 5171208, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)),
      0.0, Municipality, UnknownLinkType, InUse, NormalLinkInterface, RoadType.MunicipalityStreetRoad,
      Some("Vt5"), None, BigInt(0), None, None, Map("linkId" -> 5171208, "segmentId" -> 63298), 1, roadPartNumber1, 1, 0, 0, 0, 1, "2015-01-01", "2016-01-01",
      0.0, 0.0, SideCode.Unknown, None, None, Anomaly.None, 123),
      RoadAddressLink(0, 0, 5171208, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)),
      0.0, Municipality, UnknownLinkType, InUse, NormalLinkInterface, RoadType.MunicipalityStreetRoad,
      Some("Vt5"), None, BigInt(0), None, None, Map("linkId" -> 5171208, "segmentId" -> 63298), 1, roadPartNumber2, 1, 0, 0, 0, 1, "2015-01-01", "2016-01-01",
      0.0, 0.0, SideCode.Unknown, None, None, Anomaly.None, 123)
    )

    val partitionedRoadLinks = RoadAddressLinkPartitioner.partition(roadLinks)
    partitionedRoadLinks.size should be (2)
  }

  test("Test partition When 2 links have at least different TRACK Then they will be in different groups of RoadAddressLinks") {
    val track1 = 1
    val track2 = 2

    val roadLinks = Seq(RoadAddressLink(0, 0, 5171208, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)),
      0.0, Municipality, UnknownLinkType, InUse, NormalLinkInterface, RoadType.MunicipalityStreetRoad,
      Some("Vt5"), None, BigInt(0), None, None, Map("linkId" -> 5171208, "segmentId" -> 63298), 1, 1, track1, 0, 0, 0, 1, "2015-01-01", "2016-01-01",
      0.0, 0.0, SideCode.Unknown, None, None, Anomaly.None, 123),
      RoadAddressLink(0, 0, 5171208, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)),
      0.0, Municipality, UnknownLinkType, InUse, NormalLinkInterface, RoadType.MunicipalityStreetRoad,
      Some("Vt5"), None, BigInt(0), None, None, Map("linkId" -> 5171208, "segmentId" -> 63298), 1, 1, track2, 0, 0, 0, 1, "2015-01-01", "2016-01-01",
      0.0, 0.0, SideCode.Unknown, None, None, Anomaly.None, 123)
    )

    val partitionedRoadLinks = RoadAddressLinkPartitioner.partition(roadLinks)
    partitionedRoadLinks.size should be (2)
  }

  test("Test partition When 1 link have LinkGeomSource.ComplimentaryLinkInterface and other one have any other LinkGeomSource Then they will be in different groups of RoadAddressLinks") {

    val roadLinks = Seq(RoadAddressLink(0, 0, 5171208, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)),
      0.0, Municipality, UnknownLinkType, InUse, ComplimentaryLinkInterface, RoadType.MunicipalityStreetRoad,
      Some("Vt5"), None, BigInt(0), None, None, Map("linkId" -> 5171208, "segmentId" -> 63298), 1, 1, 1, 0, 0, 0, 1, "2015-01-01", "2016-01-01",
      0.0, 0.0, SideCode.Unknown, None, None, Anomaly.None, 123),
      RoadAddressLink(0, 0, 5171208, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)),
      0.0, Municipality, UnknownLinkType, InUse, NormalLinkInterface, RoadType.MunicipalityStreetRoad,
      Some("Vt5"), None, BigInt(0), None, None, Map("linkId" -> 5171208, "segmentId" -> 63298), 1, 1, 1, 0, 0, 0, 1, "2015-01-01", "2016-01-01",
      0.0, 0.0, SideCode.Unknown, None, None, Anomaly.None, 123)
    )

    val partitionedRoadLinks = RoadAddressLinkPartitioner.partition(roadLinks)
    partitionedRoadLinks.size should be (2)
  }

  test("Test partition When 1 link have LinkGeomSource.SuravageLinkInterface and other one have any other LinkGeomSource Then they will be in different groups of RoadAddressLinks") {

    val roadLinks = Seq(RoadAddressLink(0, 0, 5171208, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)),
      0.0, Municipality, UnknownLinkType, InUse, SuravageLinkInterface, RoadType.MunicipalityStreetRoad,
      Some("Vt5"), None, BigInt(0), None, None, Map("linkId" -> 5171208, "segmentId" -> 63298), 1, 1, 1, 0, 0, 0, 1, "2015-01-01", "2016-01-01",
      0.0, 0.0, SideCode.Unknown, None, None, Anomaly.None, 123),
      RoadAddressLink(0, 0, 5171208, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)),
      0.0, Municipality, UnknownLinkType, InUse, LinkGeomSource.Unknown, RoadType.MunicipalityStreetRoad,
      Some("Vt5"), None, BigInt(0), None, None, Map("linkId" -> 5171208, "segmentId" -> 63298), 1, 1, 1, 0, 0, 0, 1, "2015-01-01", "2016-01-01",
      0.0, 0.0, SideCode.Unknown, None, None, Anomaly.None, 123)
    )

    val partitionedRoadLinks = RoadAddressLinkPartitioner.partition(roadLinks)
    partitionedRoadLinks.size should be (2)
  }

  test("Test partition When 2 links have LinkGeomSource that are not ComplimentaryLinkInterface neither SuravageLinkInterface Then they will be in same group of RoadAddressLinks") {

    val roadLinks = Seq(RoadAddressLink(0, 0, 5171208, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)),
      0.0, Municipality, UnknownLinkType, InUse, LinkGeomSource.NormalLinkInterface, RoadType.MunicipalityStreetRoad,
      Some("Vt5"), None, BigInt(0), None, None, Map("linkId" -> 5171208, "segmentId" -> 63298), 1, 1, 1, 0, 0, 0, 1, "2015-01-01", "2016-01-01",
      0.0, 0.0, SideCode.Unknown, None, None, Anomaly.None, 123),
      RoadAddressLink(0, 0, 5171208, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)),
      0.0, Municipality, UnknownLinkType, InUse, LinkGeomSource.Unknown, RoadType.MunicipalityStreetRoad,
      Some("Vt5"), None, BigInt(0), None, None, Map("linkId" -> 5171208, "segmentId" -> 63298), 1, 1, 1, 0, 0, 0, 1, "2015-01-01", "2016-01-01",
      0.0, 0.0, SideCode.Unknown, None, None, Anomaly.None, 123)
    )

    val partitionedRoadLinks = RoadAddressLinkPartitioner.partition(roadLinks)
    partitionedRoadLinks.size should be (1)
  }

  test("Test partition When having two different Anomalies and two different roadPartNumbers Then 4 groups should be created having 2 of those groups being Anomaly.NoAddressGiven") {
    val partitioned = RoadAddressLinkPartitioner.partition(roadAddressLinks)
    partitioned.size should be (4)
    partitioned.flatten.size should be (roadAddressLinks.size)
    partitioned.count(l => l.exists(_.anomaly.value > 0)) should be (2)
  }

  test("Test partition When connecting anomalous roads into one Then they will be become combined") {
    val add = makeRoadAddressLink(0, 1, 0, 0, 1.0, 11.0)
    val mod = add.copy(geometry = Seq(Point(11.0,11.0), Point(11.0,21.0)))
    val partitioned = RoadAddressLinkPartitioner.partition(roadAddressLinks ++ Seq(mod))
    val anomalous = partitioned.filter(l => l.exists(_.anomaly.value > 0))
    anomalous.size should be (1)
  }

  test("Test partition When adding modified connected anomalous to existing roadAddressLinks Then should return same previous size, 4, of partitioned groups") {
    val add = makeRoadAddressLink(0, 1, 0, 0, 1.0, 11.0)
    val mod = add.copy(geometry = Seq(Point(10.0,21.0), Point(11.0,21.0)))
    val partitioned = RoadAddressLinkPartitioner.partition(roadAddressLinks ++ Seq(mod))
    partitioned.size should be (4)
  }

  test("Test partition When adding modified connected anomalous with Anomaly.GeometryChanged to existing roadAddressLinks Then should return same previous size plus one, 5, of partitioned groups") {
    val add = makeRoadAddressLink(0, Anomaly.GeometryChanged.value, 0, 0, 1.0, 11.0)
    val mod = add.copy(geometry = Seq(Point(10.0,21.0), Point(11.0,21.0)))
    val partitioned = RoadAddressLinkPartitioner.partition(roadAddressLinks ++ Seq(mod))
    partitioned.size should be (5)
  }

  test("Test partition When adding two combined modified but connected anomalous with Anomaly.GeometryChanged to existing roadAddressLinks Then should return same previous size plus one, 5, of partitioned groups") {
    val add = makeRoadAddressLink(0, Anomaly.GeometryChanged.value, 0, 0, 1.0, 11.0)
    val mod = add.copy(geometry = Seq(Point(10.0,21.0), Point(11.0,21.0)),
      roadNumber = 2, roadPartNumber=1, trackCode = 0)
    val add2 = makeRoadAddressLink(1536, Anomaly.None.value, 0, 0, 1.0, 11.0)
    val mod2 = add2.copy(geometry = Seq(Point(11.0,21.0), Point(11.0,22.0)),
      newGeometry = Option(Seq(Point(11.0,21.0), Point(11.0,25.0))),
      roadLinkSource = LinkGeomSource.HistoryLinkInterface,
      startAddressM = 10, endAddressM = 20, roadNumber = 2, roadPartNumber=1, trackCode = 0)
    val partitioned = RoadAddressLinkPartitioner.partition(roadAddressLinks ++ Seq(mod, mod2))
    val group = partitioned.find(_.exists(r => r.roadNumber == 2))
    partitioned.size should be (5)
    group.nonEmpty should be (true)
    group.get.size should be (2)
  }

}
