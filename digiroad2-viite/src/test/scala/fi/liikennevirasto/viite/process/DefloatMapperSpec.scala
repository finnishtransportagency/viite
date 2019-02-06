package fi.liikennevirasto.viite.process

import java.util.Date

import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.NormalLinkInterface
import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.RoadType
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.dao.{CalibrationPoint, Discontinuity, FloatingReason, RoadAddress}
import fi.liikennevirasto.viite.model.{Anomaly, RoadAddressLink}
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}

class DefloatMapperSpec extends FunSuite with Matchers{
  /*
  val sources = Seq(
    createRoadAddressLink(193080L, 1021200L, Seq(Point(653051.929,6927199.581,105.14400000000023), Point(653031.198,6927221.85,105.55199999999604), Point(653011.425,6927242.865,105.92900000000373), Point(652995.475256991,6927260.328718615,106.23299510185589)), 4846L, 1L, 0, 4035, 4118, SideCode.AgainstDigitizing, Anomaly.None),
    createRoadAddressLink(233578L, 1021217L, Seq(Point(652995.475,6927260.329,106.2329999999929), Point(652988.767,6927267.695,106.42500000000291), Point(652983.363,6927272.531,106.53200000000652)), 4846L, 1L, 0, 4018, 4035, SideCode.AgainstDigitizing, Anomaly.None)
  )
  val targets = Seq(
    createRoadAddressLink(0L, 500073990L, Seq(Point(653003.293,6927251.369,106.06299999999464), Point(652993.291,6927263.081,106.29799999999523)), 0, 0, 99, 0, 0, SideCode.AgainstDigitizing, Anomaly.NoAddressGiven),
    createRoadAddressLink(0L, 500073981L, Seq(Point(653051.929,6927199.581,105.14400000000023), Point(653031.198,6927221.85,105.55199999999604), Point(653011.425,6927242.865,105.92900000000373), Point(653003.293,6927251.369,106.06299999999464)), 0, 0, 99, 0, 0, SideCode.AgainstDigitizing, Anomaly.NoAddressGiven),
    createRoadAddressLink(0L, 500073988L, Seq(Point(652993.291,6927263.081,106.29799999999523), Point(652988.767,6927267.695,106.42500000000291), Point(652983.363,6927272.531,106.53200000000652)), 0, 0, 99, 0, 0, SideCode.AgainstDigitizing, Anomaly.NoAddressGiven)
  )
  test("test create mapping") {
    val mapping = DefloatMapper.createAddressMap(sources, targets)
    sources.forall(s => mapping.exists(_.sourceLinkId == s.linkId)) should be (true)
    targets.forall(t => mapping.exists(_.targetLinkId == t.linkId)) should be (true)
    mapping.forall(ram => ram.sourceStartM == Double.NaN) should be (false)
    mapping.forall(ram => ram.targetStartM == Double.NaN) should be (false)
    mapping.forall(ram => ram.sourceEndM == Double.NaN) should be (false)
    mapping.forall(ram => ram.targetEndM == Double.NaN) should be (false)
  }

  test("test order road address link with intersection") {
    val sources = Seq(
      createRoadAddressLink(1L, 123L, Seq(Point(422739.942,7228000.062), Point(422654.464, 7228017.876)), 1L, 1L, 0, 100, 107, SideCode.TowardsDigitizing, Anomaly.None),
      createRoadAddressLink(3L, 125L, Seq(Point(422565.724, 7228023.602), Point(422556.5834168215, 7228025.871885278)), 1L, 1L, 0, 121, 135, SideCode.TowardsDigitizing, Anomaly.None),
      createRoadAddressLink(2L, 124L, Seq(Point(422654.464, 7228017.876), Point(422565.724,7228023.602)), 1L, 1L, 0, 107, 121, SideCode.AgainstDigitizing, Anomaly.None)
    )
    val targets = Seq(
      createRoadAddressLink(0L, 457L, Seq(Point(422566.54,7228030.756), Point(422557.481, 7228032.199)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven),
      createRoadAddressLink(0L, 456L, Seq(Point(422566.54,7228030.756), Point(422598.206, 7228229.117)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven),
      createRoadAddressLink(0L, 458L, Seq(Point(422739.942,7228000.062), Point(422566.54, 7228030.756)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven)
    )
    an [IllegalArgumentException] should be thrownBy DefloatMapper.orderRoadAddressLinks(sources, targets)
  }

  test("Order road address link sources and targets") {
    val sources = Seq(
      createRoadAddressLink(1L, 123L, Seq(Point(5.0,5.0), Point(10.0, 10.0)), 1L, 1L, 0, 100, 107, SideCode.TowardsDigitizing, Anomaly.None),
      createRoadAddressLink(3L, 125L, Seq(Point(20.0, 0.0), Point(30.0, 10.0)), 1L, 1L, 0, 121, 135, SideCode.TowardsDigitizing, Anomaly.None),
      createRoadAddressLink(2L, 124L, Seq(Point(20.0, 0.0), Point(10.0,10.0)), 1L, 1L, 0, 107, 121, SideCode.AgainstDigitizing, Anomaly.None)
    )
    val targets = Seq(
      createRoadAddressLink(0L, 457L, Seq(Point(19.0,1.0), Point(20.0, 0.0), Point(30.0, 10.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven),
      createRoadAddressLink(0L, 456L, Seq(Point(5.0,5.0), Point(10.0, 10.0), Point(19.0, 1.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven)
    )
    val (ordS, ordT) = DefloatMapper.orderRoadAddressLinks(sources, targets)
    ordS.map(_.id) should be (Seq(1L, 2L, 3L))
    ordT.map(_.linkId) should be (Seq(456L, 457L))
  }

  test("Yet another test case to Order road address link sources and targets") {
    val sources = Seq(
      createRoadAddressLink(193080L, 1021200L, Seq(Point(653051.929,6927199.581,105.14400000000023), Point(653031.198,6927221.85,105.55199999999604), Point(653011.425,6927242.865,105.92900000000373), Point(652995.475256991,6927260.328718615,106.23299510185589)), 4846L, 1L, 0, 4035, 4118, SideCode.AgainstDigitizing, Anomaly.None),
      createRoadAddressLink(233578L, 1021217L, Seq(Point(652995.475,6927260.329,106.2329999999929), Point(652988.767,6927267.695,106.42500000000291), Point(652983.363,6927272.531,106.53200000000652)), 4846L, 1L, 0, 4018, 4035, SideCode.AgainstDigitizing, Anomaly.None)
    )
    val targets = Seq(
      createRoadAddressLink(0L, 500073990L, Seq(Point(653003.293,6927251.369,106.06299999999464), Point(652993.291,6927263.081,106.29799999999523)), 0, 0, 99, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven),
      createRoadAddressLink(0L, 500073981L, Seq(Point(653051.929,6927199.581,105.14400000000023), Point(653031.198,6927221.85,105.55199999999604), Point(653011.425,6927242.865,105.92900000000373), Point(653003.293,6927251.369,106.06299999999464)), 0, 0, 99, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven),
      createRoadAddressLink(0L, 500073988L, Seq(Point(652993.291,6927263.081,106.29799999999523), Point(652988.767,6927267.695,106.42500000000291), Point(652983.363,6927272.531,106.53200000000652)), 0, 0, 99, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven)
    )
    val (ordS, ordT) = DefloatMapper.orderRoadAddressLinks(sources, targets)
    ordS.map(_.id) should be (Seq(233578L, 193080L))
    ordT.map(_.linkId) should be (Seq(500073988L, 500073990L, 500073981L))
  }

  test("Order roadAddress links should preserve source side code, if the summed distance between two geometries when moved to the cartesian origin : head-to-head + tail-to-head is smaller then head-to-tail + tail-to-head "){

    /*
    Scenario: Source geom is ahead of the target geom, they are very close to adjacency as you can see the last point of the target geom is VERY close to the first point of the source geom.
     */

    val sourceGeom = Seq(
      Point(395935.249,7381457.356,92.1140000000014),
      Point(395942.058,7381473.344,91.88199999999779),
      Point(395947.564,7381488.458,91.61000000000058),
      Point(395954.445,7381517.646,91.1649999999936),
      Point(395963.576,7381549.536,90.86299999999756)
    )

    val targetGeom = Seq (
      Point(395866.195,7381359.767,93.57099999999627),
      Point(395885.888,7381380.868,93.46700000000419),
      Point(395896.607,7381393.662,93.29700000000594),
      Point(395905.257,7381405.936,93.02700000000186),
      Point(395912.772,7381417.484,92.83599999999569),
      Point(395919.076,7381426.896,92.67399999999907),
      Point(395925.199,7381438.834,92.46600000000035),
      Point(395927.73,7381444.075,92.43300000000454)
    )

    //target end = source start
    val sources = Seq(
      createRoadAddressLink(1L, 123L, sourceGeom, 1L, 1L, 0, 5774, 5871, SideCode.TowardsDigitizing, Anomaly.None)
    )
    val targets = Seq(
      createRoadAddressLink(0L, 456L, targetGeom, 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven)
    )
    val (ordS, ordT) = DefloatMapper.orderRoadAddressLinks(sources, targets)
    ordS.map(_.sideCode) should be (Seq(SideCode.TowardsDigitizing))
    ordT.map(_.sideCode) should be (Seq(SideCode.TowardsDigitizing))
  }

  test("post transfer check passes on correct input") {
    val seq = Seq(createRoadAddressLink(-1000, 2, Seq(), 1, 1, 0, 100, 104, SideCode.TowardsDigitizing, Anomaly.None)).map(roadAddressLinkToRoadAddress(false))
    val org = Seq(createRoadAddressLink(1, 1, Seq(), 1, 1, 0, 100, 102, SideCode.TowardsDigitizing, Anomaly.None),
      createRoadAddressLink(1, 2, Seq(), 1, 1, 0, 102, 104, SideCode.TowardsDigitizing, Anomaly.None)).map(roadAddressLinkToRoadAddress(false))
    DefloatMapper.postTransferChecks(seq, org)
  }

  test("post transfer check fails if target addresses are missing") {
    val seq = Seq(createRoadAddressLink(-1000, 2, Seq(), 1, 1, 0, 100, 104, SideCode.TowardsDigitizing, Anomaly.None)).map(roadAddressLinkToRoadAddress(false))
    val org = Seq(createRoadAddressLink(1, 1, Seq(), 1, 1, 0, 100, 108, SideCode.TowardsDigitizing, Anomaly.None)).map(roadAddressLinkToRoadAddress(false))
    val t = intercept[InvalidAddressDataException] {
      DefloatMapper.postTransferChecks(seq, org)
    }
    t.getMessage should be ("Generated address list does not end at 108 but 104")
  }

  test("post transfer check fails if target addresses has a gap") {
    val seq = Seq(createRoadAddressLink(-1000, 2, Seq(), 1, 1, 0, 100, 104, SideCode.TowardsDigitizing, Anomaly.None),
      createRoadAddressLink(-1000, 2, Seq(), 1, 1, 0, 105, 108, SideCode.TowardsDigitizing, Anomaly.None)).map(roadAddressLinkToRoadAddress(false))
    val org = Seq(createRoadAddressLink(1, 1, Seq(), 1, 1, 0, 100, 108, SideCode.TowardsDigitizing, Anomaly.None)).map(roadAddressLinkToRoadAddress(false))
    val t = intercept[InvalidAddressDataException] {
      DefloatMapper.postTransferChecks(seq, org)
    }
    t.getMessage should be ("Generated address list was non-continuous")
  }

  test("Should adjust road addresses to keep it without gaps") {
    val sources = Seq(
      dummyRoadAddress(1L, 1021200L, Seq(Point(0,0), Point(100,0), Point(200,0)), 1L, 1L, 0, 1111, 2222, SideCode.AgainstDigitizing, Anomaly.None, FloatingReason.ApplyChanges),
      dummyRoadAddress(2L, 1021217L, Seq(Point(200,0), Point(300,0), Point(400,0)), 1L, 1L, 0, 2222, 3333, SideCode.AgainstDigitizing, Anomaly.None, FloatingReason.ApplyChanges)
    )
    val targets = Seq(
      dummyRoadAddress(3L, 500073990L, Seq(Point(0,0), Point(100,0), Point(200,0)), 2L, 2, 99, 1112, 2222, SideCode.Unknown, Anomaly.NoAddressGiven, FloatingReason.NoFloating),
      dummyRoadAddress(4L, 500073981L, Seq(Point(200,0), Point(300,0), Point(400,0)), 2L, 2, 99, 2223, 2224, SideCode.Unknown, Anomaly.NoAddressGiven, FloatingReason.NoFloating),
      dummyRoadAddress(5L, 500073988L, Seq(Point(200,0), Point(300,0), Point(400,0)), 2L, 2, 99, 2225, 3334, SideCode.Unknown, Anomaly.NoAddressGiven, FloatingReason.NoFloating)
    )

    val result = DefloatMapper.adjustRoadAddresses(targets, sources)
    result.size should be (3)

    val head = result.find(ra => ra.id == 3L).get
    val middle = result.find(ra => ra.id == 4L).get
    val last = result.find(ra => ra.id == 5L).get
    head.startAddrMValue should be (1111)
    head.endAddrMValue should be (middle.startAddrMValue)
    last.endAddrMValue should be (3333)
    last.startAddrMValue should be(middle.endAddrMValue)
  }

  test("Choosing the correct starting link from targets - Towards"){
    //           /|
    //          / |
    //         /  |
    //        .___+ (starting point)

    val sources = Seq(
      createRoadAddressLink(1L, 1021200L, Seq(Point(0,0), Point(100, 0)), 1L, 1L, 0, 0, 100, SideCode.TowardsDigitizing, Anomaly.None),
      createRoadAddressLink(2L, 1021217L, Seq(Point(100, 0), Point(200, 0)), 1L, 1L, 0, 100, 200, SideCode.TowardsDigitizing, Anomaly.None),
      createRoadAddressLink(3L, 1021218L, Seq(Point(200, 0), Point(300, 0)), 1L, 1L, 0, 200, 300, SideCode.TowardsDigitizing, Anomaly.None)
    )
    val targets = Seq(
      createRoadAddressLink(4L, 500073988L, Seq(Point(300,0), Point(200,200)), 0L, 0, 99, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven),
      createRoadAddressLink(5L, 500073981L, Seq(Point(200,200), Point(100,300)), 0L, 0, 99, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven),
      createRoadAddressLink(6L, 500073990L, Seq(Point(100,300), Point(0,400)), 0L, 0, 99, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven)
    )

    val result = DefloatMapper.orderRoadAddressLinks(sources, targets)
    val orderedSources = result._1
    val orderedTarget = result._2

    orderedSources.size should be (3)
    orderedTarget.size should be (3)
    orderedTarget.head.id should be (6)
    orderedTarget.head.linkId should be (500073990L)
  }

  test("Choosing the correct starting link from targets - Against"){
    //        |\
    //        | \
    //        |  \
    //        +___. (starting point)

    val sources = Seq(

      createRoadAddressLink(1L, 1021218L, Seq(Point(200, 0), Point(300, 0)), 1L, 1L, 0, 0, 100, SideCode.AgainstDigitizing, Anomaly.None),
      createRoadAddressLink(2L, 1021217L, Seq(Point(100, 0), Point(200, 0)), 1L, 1L, 0, 100, 200, SideCode.AgainstDigitizing, Anomaly.None),
      createRoadAddressLink(3L, 1021200L, Seq(Point(0,0), Point(100, 0)), 1L, 1L, 0, 200, 300, SideCode.AgainstDigitizing, Anomaly.None)


    )
    val targets = Seq(
      createRoadAddressLink(4L, 500073988L, Seq(Point(300,0), Point(200,200)), 0L, 0, 99, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven),
      createRoadAddressLink(5L, 500073981L, Seq(Point(200,200), Point(100,250)), 0L, 0, 99, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven),
      createRoadAddressLink(6L, 500073990L, Seq(Point(100,250), Point(0,300)), 0L, 0, 99, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven)
    )

    val result = DefloatMapper.orderRoadAddressLinks(sources, targets)
    val orderedSources = result._1
    val orderedTarget = result._2

    orderedSources.size should be (3)
    orderedTarget.size should be (3)
    orderedTarget.head.id should be (4)
    orderedTarget.head.linkId should be (500073988L)
  }

  test("Choosing the correct starting link from targets - case with start closer to starting point"){
    //           /|
    //          / |
    //         /  |
    //        .___+ (starting point)

    val sources = Seq(

      createRoadAddressLink(1L, 1021218L, Seq(Point(0, 0), Point(100, 0)), 1L, 1L, 0, 0, 100, SideCode.TowardsDigitizing, Anomaly.None),
      createRoadAddressLink(2L, 1021217L, Seq(Point(100, 0), Point(200, 0)), 1L, 1L, 0, 100, 200, SideCode.TowardsDigitizing, Anomaly.None),
      createRoadAddressLink(3L, 1021200L, Seq(Point(200,0), Point(300, 0)), 1L, 1L, 0, 200, 300, SideCode.TowardsDigitizing, Anomaly.None)


    )
    val targets = Seq(
      createRoadAddressLink(4L, 500073988L, Seq(Point(0,100), Point(100,70)), 0L, 0, 99, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven),
      createRoadAddressLink(5L, 500073981L, Seq(Point(100,70), Point(200,30)), 0L, 0, 99, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven),
      createRoadAddressLink(6L, 500073990L, Seq(Point(200,30), Point(300,0)), 0L, 0, 99, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven)
    )

    val result = DefloatMapper.orderRoadAddressLinks(sources, targets)
    val orderedSources = result._1
    val orderedTarget = result._2

    orderedSources.size should be (3)
    orderedTarget.size should be (3)
    orderedTarget.head.id should be (4)
    orderedTarget.head.linkId should be (500073988L)
  }

  test("Choosing the correct starting link from targets - case with start closer to starting point - Against"){
    //        |\
    //        | \
    //        |  \
    //        +___. (starting point)

    val sources = Seq(

      createRoadAddressLink(1L, 1021218L, Seq(Point(200, 0), Point(300, 0)), 1L, 1L, 0, 0, 100, SideCode.AgainstDigitizing, Anomaly.None),
      createRoadAddressLink(2L, 1021217L, Seq(Point(100, 0), Point(200, 0)), 1L, 1L, 0, 100, 200, SideCode.AgainstDigitizing, Anomaly.None),
      createRoadAddressLink(3L, 1021200L, Seq(Point(0,0), Point(100, 0)), 1L, 1L, 0, 200, 300, SideCode.AgainstDigitizing, Anomaly.None)
    )
    val targets = Seq(
      createRoadAddressLink(4L, 500073988L, Seq(Point(0,100), Point(100,70)), 0L, 0, 99, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven),
      createRoadAddressLink(5L, 500073981L, Seq(Point(100,70), Point(200,30)), 0L, 0, 99, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven),
      createRoadAddressLink(6L, 500073990L, Seq(Point(200,30), Point(300,0)), 0L, 0, 99, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven)
    )

    val result = DefloatMapper.orderRoadAddressLinks(sources, targets)
    val orderedSources = result._1
    val orderedTarget = result._2

    orderedSources.size should be (3)
    orderedTarget.size should be (3)
    orderedTarget.head.id should be (6)
    orderedTarget.head.linkId should be (500073990L)
  }

  test("Choosing the correct starting link from targets - linear cases that change position forward"){
    //
    //     Sources   Targets
    //
    //     |--|--|   |--|--|

    val sources = Seq(
      createRoadAddressLink(1L, 1021218L, Seq(Point(0, 0), Point(100, 0)), 1L, 1L, 0, 0, 100, SideCode.TowardsDigitizing, Anomaly.None),
      createRoadAddressLink(2L, 1021217L, Seq(Point(100, 0), Point(200, 0)), 1L, 1L, 0, 100, 200, SideCode.TowardsDigitizing, Anomaly.None)
    )
    val targets = Seq(
      createRoadAddressLink(3L, 500073988L, Seq(Point(300,0), Point(400,0)), 0L, 0, 99, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven),
      createRoadAddressLink(4L, 500073981L, Seq(Point(400,0), Point(500,0)), 0L, 0, 99, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven)
    )

    val result = DefloatMapper.orderRoadAddressLinks(sources, targets)
    val orderedSources = result._1
    val orderedTarget = result._2

    orderedSources.size should be (2)
    orderedTarget.size should be (2)
    orderedTarget.head.id should be (3)
    orderedTarget.head.linkId should be (500073988L)
  }



  private def createRoadAddressLink(id: Long, linkId: Long, geom: Seq[Point], roadNumber: Long, roadPartNumber: Long, trackCode: Long,
                                    startAddressM: Long, endAddressM: Long, sideCode: SideCode, anomaly: Anomaly, startCalibrationPoint: Boolean = false,
                                    endCalibrationPoint: Boolean = false) = {
    val length = GeometryUtils.geometryLength(geom)
    val startCP = if (startCalibrationPoint) {
      Option(CalibrationPoint(linkId, if (sideCode == SideCode.TowardsDigitizing) 0.0 else length, startAddressM))
    } else {
      None
    }
    val endCP = if (endCalibrationPoint) {
      Option(CalibrationPoint(linkId, if (sideCode == SideCode.AgainstDigitizing) 0.0 else length, endAddressM))
    } else {
      None
    }
    RoadAddressLink(id, id, linkId, geom, length, State, LinkType.apply(1),
      ConstructionType.InUse, NormalLinkInterface, RoadType.PublicRoad, Some("Vt5"), None, BigInt(0), None, None, Map(), roadNumber, roadPartNumber,
      trackCode, 1, 5, startAddressM, endAddressM, "2016-01-01", "", 0.0, length, sideCode, startCP, endCP, anomaly)
  }

  private def roadAddressLinkToRoadAddress(floating: Boolean)(l: RoadAddressLink) = {
    //TODO road address now have the linear location check this value here
    RoadAddress(l.id, 1L, l.roadNumber, l.roadPartNumber, RoadType.Unknown, Track.apply(l.trackCode.toInt), Discontinuity.apply(l.discontinuity.toInt),
      l.startAddressM, l.endAddressM, Option(new DateTime(new Date())), None, None, l.linkId, l.startMValue, l.endMValue, l.sideCode, l.attributes.getOrElse("ADJUSTED_TIMESTAMP", 0L).asInstanceOf[Long],
      (l.startCalibrationPoint, l.endCalibrationPoint), if (floating) FloatingReason.ApplyChanges else NoFloating, l.geometry, l.roadLinkSource, l.elyCode, NoTermination, 0)
  }

  private def dummyRoadAddress(id: Long, linkId: Long, geom: Seq[Point], roadNumber: Long, roadPartNumber: Long, trackCode: Long,
                               startAddressM: Long, endAddressM: Long, sideCode: SideCode, anomaly: Anomaly, floating: FloatingReason) = {
    //TODO road address now have the linear location check this value here
    RoadAddress(id, 1L, roadNumber, roadPartNumber, RoadType.Unknown, Track.apply(trackCode.toInt), Discontinuity.Continuous,
      startAddressM, endAddressM, Option(new DateTime(new Date())), None, None, linkId, 0, GeometryUtils.geometryLength(geom), sideCode, 0L,
      (None, None), floating, geom, LinkGeomSource.NormalLinkInterface, 1, NoTermination, 0)
  }*/
}
