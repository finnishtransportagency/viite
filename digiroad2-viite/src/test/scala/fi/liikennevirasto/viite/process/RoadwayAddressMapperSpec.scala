package fi.liikennevirasto.viite.process

import java.util.Date

import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.NormalLinkInterface
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.service.RoadLinkType.NormalRoadLinkType
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import fi.liikennevirasto.viite.RoadType
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.{Anomaly, RoadAddressLink}
import org.joda.time.DateTime
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSuite, Matchers}

class RoadwayAddressMapperSpec extends FunSuite with Matchers{

  val mockRoadAddressDAO: RoadAddressDAO = MockitoSugar.mock[RoadAddressDAO]


  test("Should map current roadway linear location into road addresses"){
    val roadwayAddressMapper = new RoadwayAddressMapper(mockRoadAddressDAO)

    val roadwayId = 12L
    val startDate = new DateTime(2007, 1, 1, 0, 0)

    val roadwayAddress = RoadwayAddress(2L, roadwayId, 1551, 2, RoadType.PublicRoad, Track.Combined, Discontinuity.Discontinuous, 140, 300, false, startDate, None, "test_user", None, 0, NoTermination, startDate)
    val linearLocations = Seq(
      LinearLocation(2L, 1, 125, 45.0, 105.0, SideCode.TowardsDigitizing, 0, (None, None), NoFloating, Seq(Point(45.0,0.0), Point(105.0,0.0)), LinkGeomSource.NormalLinkInterface, roadwayId),
      LinearLocation(3L, 2, 123, 0.0, 49.0, SideCode.TowardsDigitizing, 0, (None, Some(250)), NoFloating, Seq(Point(105.0,0.0), Point(154.0,0.0)), LinkGeomSource.NormalLinkInterface, roadwayId),
      LinearLocation(4L, 3, 124, 0.0, 51.0, SideCode.TowardsDigitizing, 0, (Some(250), Some(300)), NoFloating, Seq(Point(154.0,0.0), Point(205.0,0.0)), LinkGeomSource.NormalLinkInterface, roadwayId)
    )

    val roadAddresses = roadwayAddressMapper.mapRoadAddresses(roadwayAddress, linearLocations)

    roadAddresses.size should be (3)
  }

  test("Should map history roadway linear location into road addresses"){
    val roadwayAddressMapper = new RoadwayAddressMapper(mockRoadAddressDAO)

    val roadwayId = 12L
    val startDate = new DateTime(1975, 7, 1, 0, 0)
    val endDate = new DateTime(2006, 12, 31, 0, 0)

    val roadwayAddress = RoadwayAddress(3L, roadwayId, 1551, 2, RoadType.PublicRoad, Track.Combined, Discontinuity.Discontinuous, 240, 400, false, startDate, Some(endDate), "test_user", None, 0, NoTermination, startDate)
    val linearLocations = Seq(
      LinearLocation(2L, 1, 125, 45.0, 105.0, SideCode.TowardsDigitizing, 0, (None, None), NoFloating, Seq(Point(45.0,0.0), Point(105.0,0.0)), LinkGeomSource.NormalLinkInterface, roadwayId),
      LinearLocation(3L, 2, 123, 0.0, 49.0, SideCode.TowardsDigitizing, 0, (None, Some(250)), NoFloating, Seq(Point(105.0,0.0), Point(154.0,0.0)), LinkGeomSource.NormalLinkInterface, roadwayId),
      LinearLocation(4L, 3, 124, 0.0, 51.0, SideCode.TowardsDigitizing, 0, (Some(250), Some(300)), NoFloating, Seq(Point(154.0,0.0), Point(205.0,0.0)), LinkGeomSource.NormalLinkInterface, roadwayId)
    )

    val roadAddresses = roadwayAddressMapper.mapRoadAddresses(roadwayAddress, linearLocations)

    roadAddresses.size should be (3)
  }
}
