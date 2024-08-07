package fi.liikennevirasto.viite.model

import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, LifecycleStatus, LinkGeomSource, RoadPart, SideCode}
// Used in debugging when needed.
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
  * Created by venholat on 21.10.2016.
  */
class RoadAddressLinkPartitionerSpec extends AnyFunSuite with Matchers {
  private lazy val roadAddressLinks = Seq(
    makeRoadAddressLink( 1, 0, RoadPart(1, 1)),
    makeRoadAddressLink( 2, 0, RoadPart(1, 1)),
    makeRoadAddressLink( 3, 0, RoadPart(1, 1)),
    makeRoadAddressLink( 4, 0, RoadPart(1, 1)),
    makeRoadAddressLink( 5, 0, RoadPart(1, 1)),
    makeRoadAddressLink( 6, 0, RoadPart(1, 1)),
    makeRoadAddressLink( 7, 0, RoadPart(1, 1)),
    makeRoadAddressLink(11, 0, RoadPart(1, 2)),
    makeRoadAddressLink(12, 0, RoadPart(1, 2)),
    makeRoadAddressLink(13, 0, RoadPart(1, 2)),
    makeRoadAddressLink(14, 0, RoadPart(1, 2)),
    makeRoadAddressLink(15, 0, RoadPart(1, 2)),
    makeRoadAddressLink(16, 0, RoadPart(1, 2)),
    makeRoadAddressLink(17, 0, RoadPart(1, 2)),
    makeRoadAddressLink( 0, 1, RoadPart(0, 0),  1.0,  1.0),
    makeRoadAddressLink( 0, 1, RoadPart(0, 0), 11.0,  1.0),
    makeRoadAddressLink( 0, 1, RoadPart(0, 0), 21.0,  1.0),
    makeRoadAddressLink( 0, 1, RoadPart(0, 0), 31.0,  1.0),
    makeRoadAddressLink( 0, 1, RoadPart(0, 0), 41.0,  1.0),
    makeRoadAddressLink( 0, 1, RoadPart(0, 0),  1.0, 11.0),
    makeRoadAddressLink( 0, 1, RoadPart(0, 0), 11.0, 11.0),
    makeRoadAddressLink( 0, 1, RoadPart(0, 0), 21.0, 11.0),
    makeRoadAddressLink( 0, 1, RoadPart(0, 0), 31.0, 11.0),
    makeRoadAddressLink( 0, 1, RoadPart(0, 0), 41.0, 11.0)
  )

  private def makeRoadAddressLink(id: Long, anomaly: Int, roadPart: RoadPart, deltaX: Double = 0.0, deltaY: Double = 0.0) = {
    RoadAddressLink(id, id, id.toString, Seq(Point(id * 10.0 + deltaX, anomaly * 10.0 + deltaY), Point((id + 1) * 10.0 + deltaX, anomaly * 10.0 + deltaY)), 10.0, AdministrativeClass.State, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, AdministrativeClass.State, None, BigInt(0), "", None, None, roadPart, 1, 1, 1, AddrMRange(id * 10, (id + 1) * 10), "", "", 0.0, 10.0, SideCode.TowardsDigitizing, None, None, sourceId = "")
  }

  test("Test partition should have specific fields (still to be defined) not empty") {

    val roadLinks = {
      Seq(RoadAddressLink(0, 0, 5171208.toString, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)), 0.0, AdministrativeClass.Municipality, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, AdministrativeClass.Municipality, None, BigInt(0), "", None, None, RoadPart(5, 205), 1, 0, 0, AddrMRange(0, 1), "2015-01-01", "2016-01-01", 0.0, 0.0, SideCode.Unknown, None, None, 123, sourceId = ""))
    }

    val partitionedRoadLinks = RoadAddressLinkPartitioner.groupByHomogeneousSection(roadLinks)

    val roadPart = partitionedRoadLinks.head.head.roadPart
    val trackCode = partitionedRoadLinks.head.head.trackCode
    val segmentId = partitionedRoadLinks.head.head.id
    val lifecycleStatus = partitionedRoadLinks.head.head.lifecycleStatus
    val roadwayNumber = partitionedRoadLinks.head.head.roadwayNumber

    segmentId should not be None
    roadPart should be(RoadPart(5, 205))
    trackCode should be(1)
    lifecycleStatus should be(LifecycleStatus.InUse)
    roadwayNumber should be(123)

  }

  test("Test partition When 2 links have at least different ROADNUMBER Then they will be in different groups of RoadAddressLinks") {
    val roadNumber1 = 1
    val roadNumber2 = 2

    val roadLinks = Seq(
      RoadAddressLink(0, 0, 5171208.toString, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)), 0.0, AdministrativeClass.Municipality, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, AdministrativeClass.Municipality, None, BigInt(0), "", None, None, RoadPart(roadNumber1, 205), 1, 0, 0, AddrMRange(0, 1), "2015-01-01", "2016-01-01", 0.0, 0.0, SideCode.Unknown, None, None, 123, sourceId = ""),
      RoadAddressLink(0, 0, 5171208.toString, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)), 0.0, AdministrativeClass.Municipality, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, AdministrativeClass.Municipality, None, BigInt(0), "", None, None, RoadPart(roadNumber2, 205), 1, 0, 0, AddrMRange(0, 1), "2015-01-01", "2016-01-01", 0.0, 0.0, SideCode.Unknown, None, None, 123, sourceId = "")
    )

    val partitionedRoadLinks = RoadAddressLinkPartitioner.groupByHomogeneousSection(roadLinks)
    partitionedRoadLinks.size should be (2)
  }

  test("Test partition When 2 links have at least different ROADPARTNUMBER Then they will be in different groups of RoadAddressLinks") {
    val roadPartNumber1 = 1
    val roadPartNumber2 = 2

    val roadLinks = Seq(
      RoadAddressLink(0, 0, 5171208.toString, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)), 0.0, AdministrativeClass.Municipality, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, AdministrativeClass.Municipality, None, BigInt(0), "", None, None, RoadPart(1, roadPartNumber1), 1, 0, 0, AddrMRange(0, 1), "2015-01-01", "2016-01-01", 0.0, 0.0, SideCode.Unknown, None, None, 123, sourceId = ""),
      RoadAddressLink(0, 0, 5171208.toString, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)), 0.0, AdministrativeClass.Municipality, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, AdministrativeClass.Municipality, None, BigInt(0), "", None, None, RoadPart(1, roadPartNumber2), 1, 0, 0, AddrMRange(0, 1), "2015-01-01", "2016-01-01", 0.0, 0.0, SideCode.Unknown, None, None, 123, sourceId = "")
    )

    val partitionedRoadLinks = RoadAddressLinkPartitioner.groupByHomogeneousSection(roadLinks)
    partitionedRoadLinks.size should be (2)
  }

  test("Test partition When 2 links have different TRACK and same ROAD NUMBER & ROAD PART NUMBER Then they will be in the same RoadAddressLink group") {
    val roadNumber1 = 1
    val roadPartNumber1 = 1
    val track1 = 1
    val track2 = 2

    val roadLinks = Seq(
      RoadAddressLink(0, 0, 5171208.toString, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)), 0.0, AdministrativeClass.Municipality, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, AdministrativeClass.Municipality, None, BigInt(0), "", None, None, RoadPart(roadNumber1, roadPartNumber1), track1, 0, 0, AddrMRange(0, 1), "2015-01-01", "2016-01-01", 0.0, 0.0, SideCode.Unknown, None, None, 123, sourceId = ""),
      RoadAddressLink(0, 0, 5171208.toString, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)), 0.0, AdministrativeClass.Municipality, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, AdministrativeClass.Municipality, None, BigInt(0), "", None, None, RoadPart(roadNumber1, roadPartNumber1), track2, 0, 0, AddrMRange(0, 1), "2015-01-01", "2016-01-01", 0.0, 0.0, SideCode.Unknown, None, None, 123, sourceId = "")
    )

    val partitionedRoadLinks = RoadAddressLinkPartitioner.groupByHomogeneousSection(roadLinks)
    partitionedRoadLinks.size should be (1)
  }

  test("Test partition When 1 link have LinkGeomSource.ComplimentaryLinkInterface and other one have any other LinkGeomSource Then they will be in different groups of RoadAddressLinks") {

    val roadLinks = Seq(
      RoadAddressLink(0, 0, 5171208.toString, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)), 0.0, AdministrativeClass.Municipality, LifecycleStatus.InUse, LinkGeomSource.ComplementaryLinkInterface, AdministrativeClass.Municipality, None, BigInt(0), "", None, None, RoadPart(1, 1), 1, 0, 0, AddrMRange(0, 1), "2015-01-01", "2016-01-01", 0.0, 0.0, SideCode.Unknown, None, None, 123, sourceId = ""),
      RoadAddressLink(0, 0, 5171208.toString, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)), 0.0, AdministrativeClass.Municipality, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface,        AdministrativeClass.Municipality, None, BigInt(0), "", None, None, RoadPart(1, 1), 1, 0, 0, AddrMRange(0, 1), "2015-01-01", "2016-01-01", 0.0, 0.0, SideCode.Unknown, None, None, 123, sourceId = "")
    )

    val partitionedRoadLinks = RoadAddressLinkPartitioner.groupByHomogeneousSection(roadLinks)
    partitionedRoadLinks.size should be (2)
  }

  test("Test partition When 2 links have LinkGeomSource that are not ComplimentaryLinkInterface neither SuravageLinkInterface Then they will be in same group of RoadAddressLinks") {

    val roadLinks = Seq(
      RoadAddressLink(0, 0, 5171208.toString, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)), 0.0, AdministrativeClass.Municipality, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, AdministrativeClass.Municipality, None, BigInt(0), "", None, None, RoadPart(1, 1), 1, 0, 0, AddrMRange(0, 1), "2015-01-01", "2016-01-01", 0.0, 0.0, SideCode.Unknown, None, None, 123, sourceId = ""),
      RoadAddressLink(0, 0, 5171208.toString, Seq(Point(532837.14110884, 6993543.6296834), Point(533388.14110884, 6994014.1296834)), 0.0, AdministrativeClass.Municipality, LifecycleStatus.InUse, LinkGeomSource.Unknown,             AdministrativeClass.Municipality, None, BigInt(0), "", None, None, RoadPart(1, 1), 1, 0, 0, AddrMRange(0, 1), "2015-01-01", "2016-01-01", 0.0, 0.0, SideCode.Unknown, None, None, 123, sourceId = "")
    )

    val partitionedRoadLinks = RoadAddressLinkPartitioner.groupByHomogeneousSection(roadLinks)
    partitionedRoadLinks.size should be (1)
  }

  test("Test partition When having two different roadPartNumbers and two separate unaddressed chains Then 4 groups should be created") {
    val partitioned = RoadAddressLinkPartitioner.groupByHomogeneousSection(roadAddressLinks)
    partitioned.size should be (4)
    partitioned.flatten.size should be (roadAddressLinks.size)
  }

  test("Test partition When connecting unaddressed link chains into one Then they will be become a homogeneous section") {
    /*  y
      * |       A
      * | -------------   <-- separate link chain A
      * |   |  mod        <-- mod will combine these separate link chains
      * | -------------   <-- separate link chain B
      * |       B
      * |____________________x
      * */

    val add = makeRoadAddressLink(0, 1, RoadPart(0, 0), 1.0, 11.0)
    val mod = add.copy(geometry = Seq(Point(11.0,11.0), Point(11.0,21.0)), sourceId = "")
    val homogeneousSections = RoadAddressLinkPartitioner.groupByHomogeneousSection(roadAddressLinks ++ Seq(mod))
    val unaddressedSections = homogeneousSections.filter(l => l.forall(_.roadPart.roadNumber == 0))
    unaddressedSections.size should be (1)
  }

  test("Test partition When adding unaddressed link that is connected to one of the two unaddressed link chains Then should return same previous size, 4, of partitioned groups") {
    val add = makeRoadAddressLink(0, 1, RoadPart(0, 0), 1.0, 11.0)
    val mod = add.copy(geometry = Seq(Point(10.0,21.0), Point(11.0,21.0)), sourceId = "")
    val partitioned = RoadAddressLinkPartitioner.groupByHomogeneousSection(roadAddressLinks ++ Seq(mod))
    partitioned.size should be (4)
  }

}
