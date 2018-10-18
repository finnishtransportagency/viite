package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.NormalLinkInterface
import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.client.vvh.{ChangeInfo, ChangeType}
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import fi.liikennevirasto.viite.RoadType
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.dao.{Discontinuity, FloatingReason, LinkStatus, ProjectLink}
import fi.liikennevirasto.viite.process.strategy.DefaultSectionCalculatorStrategy
import org.scalatest.{FunSuite, Matchers}
import fi.liikennevirasto.viite.Dummies._
import fi.liikennevirasto.viite.process.RoadAddressFiller.ChangeSet

class ApplyChangeInfoProcessSpec extends FunSuite with Matchers {

  test("Test applyChange When the road link is lengthened at the start Then linear locations measure should be adjusted") {

    val linearLocations = Seq(
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 2L, roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L, Seq(0.0, 10.0, 25.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L, Seq(0.0, 10.0), NormalLinkInterface)
    )

    val changes = Seq(
      dummyChangeInfo(ChangeType.LengthenedCommonPart, oldId = 123L, newId = 123L, oldStartMeasure = 0.0, oldEndMeasure = 20.0, newStartMeasure = 5.0, newEndMeasure = 25.0, vvhTimeStamp = 1L),
      dummyNewChangeInfo(ChangeType.LengthenedNewPart, newId = 123L, newStartMeasure = 0.0, newEndMeasure = 5.0, vvhTimeStamp = 1L)
    )

    val (adjustedLinearLocations, changeSet) = ApplyChangeInfoProcess.applyChanges(linearLocations, roadLinks, changes)

    val linearLocationOne = adjustedLinearLocations.find(_.id == 1L).get

    linearLocationOne.startMValue should be (0.0)
    linearLocationOne.endMValue should be (12.5)
    linearLocationOne.sideCode should be (SideCode.TowardsDigitizing)

    val linearLocationTwo = adjustedLinearLocations.find(_.id == 2L).get

    linearLocationTwo.startMValue should be (12.5)
    linearLocationTwo.endMValue should be (25.0)
    linearLocationOne.sideCode should be (SideCode.TowardsDigitizing)

    changeSet.droppedSegmentIds.size should be (2)
    changeSet.droppedSegmentIds should contain only (1,2)
    changeSet.newLinearLocations should contain only (linearLocationOne, linearLocationTwo)
  }

  test("Test applyChange When the road link is lengthened at the end Then linear locations measure should be adjusted") {

    val linearLocations = Seq(
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 2L, roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L, Seq(0.0, 10.0, 25.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L, Seq(0.0, 10.0), NormalLinkInterface)
    )

    val changes = Seq(
      dummyChangeInfo(ChangeType.LengthenedCommonPart, oldId = 123L, newId = 123L, oldStartMeasure = 0.0, oldEndMeasure = 20.0, newStartMeasure = 0.0, newEndMeasure = 20.0, vvhTimeStamp = 1L),
      dummyNewChangeInfo(ChangeType.LengthenedNewPart, newId = 123L, newStartMeasure = 20.0, newEndMeasure = 25.0, vvhTimeStamp = 1L)
    )

    val (adjustedLinearLocations, changeSet) = ApplyChangeInfoProcess.applyChanges(linearLocations, roadLinks, changes)

    val linearLocationOne = adjustedLinearLocations.find(_.id == 1L).get

    linearLocationOne.startMValue should be (0.0)
    linearLocationOne.endMValue should be (12.5)
    linearLocationOne.sideCode should be (SideCode.TowardsDigitizing)

    val linearLocationTwo = adjustedLinearLocations.find(_.id == 2L).get

    linearLocationTwo.startMValue should be (12.5)
    linearLocationTwo.endMValue should be (25.0)
    linearLocationOne.sideCode should be (SideCode.TowardsDigitizing)

    changeSet.droppedSegmentIds.size should be (2)
    changeSet.droppedSegmentIds should contain only (1,2)
    changeSet.newLinearLocations should contain only (linearLocationOne, linearLocationTwo)
  }

  test("Test applyChange When the road link is divided in second linear location Then linear locations measure should be adjusted and also divided") {

    val linearLocations = Seq(
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 2L, roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 126L, Seq(0.0, 12.0), NormalLinkInterface),
      dummyRoadLink(linkId = 127L, Seq(12.0, 20.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L, Seq(0.0, 10.0), NormalLinkInterface)
    )

    val changes = Seq(
      dummyChangeInfo(ChangeType.DividedModifiedPart, oldId = 123L, newId = 126L, oldStartMeasure = 0.0, oldEndMeasure = 12.0, newStartMeasure = 0.0, newEndMeasure = 12.0, vvhTimeStamp = 1L),
      dummyChangeInfo(ChangeType.DividedNewPart, oldId = 123L, newId = 127L, oldStartMeasure = 12.0, oldEndMeasure = 20.0, newStartMeasure = 0.0, newEndMeasure = 8.0, vvhTimeStamp = 1L)
    )

    val (adjustedLinearLocations, changeSet) = ApplyChangeInfoProcess.applyChanges(linearLocations, roadLinks, changes)

    val newLinearLocations = adjustedLinearLocations.filter(_.id == -1000).sortBy(_.orderNumber).toArray

    val linearLocationOne = newLinearLocations(0)

    linearLocationOne.startMValue should be (0.0)
    linearLocationOne.endMValue should be (10.0)
    linearLocationOne.linkId should be (126L)
    linearLocationOne.sideCode should be (SideCode.TowardsDigitizing)

    val linearLocationTwo = newLinearLocations(1)

    linearLocationTwo.startMValue should be (10.0)
    linearLocationTwo.endMValue should be (12.0)
    linearLocationTwo.linkId should be (126L)
    linearLocationTwo.sideCode should be (SideCode.TowardsDigitizing)

    val linearLocationThree = newLinearLocations(2)

    linearLocationThree.startMValue should be (0.0)
    linearLocationThree.endMValue should be (8.0)
    linearLocationThree.linkId should be (127L)
    linearLocationThree.sideCode should be (SideCode.TowardsDigitizing)

    every (newLinearLocations.map(_.orderNumber).toList) should (be >= 1.0 and be < 3.0)

    changeSet.droppedSegmentIds.size should be (2)
    changeSet.droppedSegmentIds should contain only (1,2)
    changeSet.newLinearLocations should contain only (linearLocationOne, linearLocationTwo, linearLocationThree)

  }

  test("Test applyChange When the road link is divided in first linear location Then linear locations measure should be adjusted and also divided") {

    val linearLocations = Seq(
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 2L, roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 126L, Seq(0.0, 8.0), NormalLinkInterface),
      dummyRoadLink(linkId = 127L, Seq(8.0, 20.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L, Seq(0.0, 10.0), NormalLinkInterface)
    )

    val changes = Seq(
      dummyChangeInfo(ChangeType.DividedModifiedPart, oldId = 123L, newId = 126L, oldStartMeasure = 0.0, oldEndMeasure = 8.0, newStartMeasure = 0.0, newEndMeasure = 8.0, vvhTimeStamp = 1L),
      dummyChangeInfo(ChangeType.DividedNewPart, oldId = 123L, newId = 127L, oldStartMeasure = 8.0, oldEndMeasure = 20.0, newStartMeasure = 0.0, newEndMeasure = 12.0, vvhTimeStamp = 1L)
    )

    val (adjustedLinearLocations, changeSet) = ApplyChangeInfoProcess.applyChanges(linearLocations, roadLinks, changes)

    val newLinearLocations = adjustedLinearLocations.filter(_.id == -1000).sortBy(_.orderNumber).toArray

    val linearLocationOne = newLinearLocations(0)

    linearLocationOne.startMValue should be (0.0)
    linearLocationOne.endMValue should be (8.0)
    linearLocationOne.linkId should be (126L)
    linearLocationOne.sideCode should be (SideCode.TowardsDigitizing)

    val linearLocationTwo = newLinearLocations(1)

    linearLocationTwo.startMValue should be (0.0)
    linearLocationTwo.endMValue should be (2.0)
    linearLocationTwo.linkId should be (127L)
    linearLocationTwo.sideCode should be (SideCode.TowardsDigitizing)

    val linearLocationThree = newLinearLocations(2)

    linearLocationThree.startMValue should be (2.0)
    linearLocationThree.endMValue should be (12.0)
    linearLocationThree.linkId should be (127L)
    linearLocationThree.sideCode should be (SideCode.TowardsDigitizing)

    every (newLinearLocations.map(_.orderNumber).toList) should (be >= 1.0 and be < 3.0)

    changeSet.droppedSegmentIds.size should be (2)
    changeSet.droppedSegmentIds should contain only (1,2)
    changeSet.newLinearLocations should contain only (linearLocationOne, linearLocationTwo, linearLocationThree)

  }

  test("Test applyChange When the road link is combined Then linear locations measure should be adjusted and also combined") {

    val linearLocations = Seq(
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 2L, roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L, Seq(0.0, 10.0, 20.0), NormalLinkInterface),
      dummyRoadLink(linkId = 127L, Seq(20.0, 30.0, 40.0), NormalLinkInterface)
    )

    val changes = Seq(
      dummyChangeInfo(ChangeType.CombinedModifiedPart, oldId = 124L, newId = 127L, oldStartMeasure = 0.0, oldEndMeasure = 10.0, newStartMeasure = 0.0, newEndMeasure = 10.0, vvhTimeStamp = 1L),
      dummyChangeInfo(ChangeType.CombinedRemovedPart, oldId = 125L, newId = 127L, oldStartMeasure = 0.0, oldEndMeasure = 10.0, newStartMeasure = 10.0, newEndMeasure = 20.0, vvhTimeStamp = 1L)
    )

    val (adjustedLinearLocations, changeSet) = ApplyChangeInfoProcess.applyChanges(linearLocations, roadLinks, changes)

    val newLinearLocations = adjustedLinearLocations.filter(_.id == -1000).sortBy(_.orderNumber).toArray

    val linearLocationOne = newLinearLocations(0)

    linearLocationOne.startMValue should be (0.0)
    linearLocationOne.endMValue should be (10.0)
    linearLocationOne.linkId should be (127L)
    linearLocationOne.sideCode should be (SideCode.TowardsDigitizing)

    val linearLocationTwo = newLinearLocations(1)

    linearLocationTwo.startMValue should be (10.0)
    linearLocationTwo.endMValue should be (20.0)
    linearLocationTwo.linkId should be (127L)
    linearLocationTwo.sideCode should be (SideCode.TowardsDigitizing)

    every (newLinearLocations.map(_.orderNumber).toList) should (be >= 1.0 and be < 5.0)

    changeSet.droppedSegmentIds.size should be (2)
    changeSet.droppedSegmentIds should contain only (4,3)
    changeSet.newLinearLocations should contain only (linearLocationOne, linearLocationTwo)

  }

  test("Test applyChange When the road link is shortened at the start Then linear locations measure should be adjusted") {

    val linearLocations = Seq(
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 2L, roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L, Seq(0.0, 5.0, 15.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L, Seq(0.0, 10.0), NormalLinkInterface)
    )

    val changes = Seq(
      dummyChangeInfo(ChangeType.ShortenedCommonPart, oldId = 123L, newId = 123L, oldStartMeasure = 5.0, oldEndMeasure = 20.0, newStartMeasure = 0.0, newEndMeasure = 15.0, vvhTimeStamp = 1L),
      dummyOldChangeInfo(ChangeType.ShortenedRemovedPart, oldId = 123L, oldStartMeasure = 0.0, oldEndMeasure = 5.0, vvhTimeStamp = 1L)
    )

    val (adjustedLinearLocations, changeSet) = ApplyChangeInfoProcess.applyChanges(linearLocations, roadLinks, changes)

    val linearLocationOne = adjustedLinearLocations.find(_.id == 1L).get

    linearLocationOne.startMValue should be (0.0)
    linearLocationOne.endMValue should be (7.5)
    linearLocationOne.sideCode should be (SideCode.TowardsDigitizing)

    val linearLocationTwo = adjustedLinearLocations.find(_.id == 2L).get

    linearLocationTwo.startMValue should be (7.5)
    linearLocationTwo.endMValue should be (15.0)
    linearLocationOne.sideCode should be (SideCode.TowardsDigitizing)

    changeSet.droppedSegmentIds.size should be (2)
    changeSet.droppedSegmentIds should contain only (1,2)
    changeSet.newLinearLocations should contain only (linearLocationOne, linearLocationTwo)

  }

  test("Test applyChange When the road link is shortened more than 10 meters Then linear locations should not be adjusted") {

    val linearLocations = Seq(
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 30.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L, Seq(0.0, 10.0, 20.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L, Seq(0.0, 10.0), NormalLinkInterface)
    )

    val changes = Seq(
      dummyChangeInfo(ChangeType.ShortenedCommonPart, oldId = 123L, newId = 123L, oldStartMeasure = 10.0, oldEndMeasure = 30.0, newStartMeasure = 0.0, newEndMeasure = 20.0, vvhTimeStamp = 1L),
      dummyOldChangeInfo(ChangeType.ShortenedRemovedPart, oldId = 123L, oldStartMeasure = 0.0, oldEndMeasure = 10.0, vvhTimeStamp = 1L)
    )

    val (adjustedLinearLocations, changeSet) = ApplyChangeInfoProcess.applyChanges(linearLocations, roadLinks, changes)

    adjustedLinearLocations.sortBy(_.id) should be (linearLocations)
    changeSet should be (ChangeSet(Set.empty, Seq.empty, Seq.empty, Seq.empty))
  }

  test("Test applyChange When the road link is shortened at the end Then linear locations measure should be adjusted") {

    val linearLocations = Seq(
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 2L, roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L, Seq(0.0, 5.0, 15.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L, Seq(0.0, 10.0), NormalLinkInterface)
    )

    val changes = Seq(
      dummyChangeInfo(ChangeType.ShortenedCommonPart, oldId = 123L, newId = 123L, oldStartMeasure = 0.0, oldEndMeasure = 15.0, newStartMeasure = 0.0, newEndMeasure = 15.0, vvhTimeStamp = 1L),
      dummyOldChangeInfo(ChangeType.ShortenedRemovedPart, oldId = 123L, oldStartMeasure = 15.0, oldEndMeasure = 20.0, vvhTimeStamp = 1L)
    )

    val (adjustedLinearLocations, changeSet) = ApplyChangeInfoProcess.applyChanges(linearLocations, roadLinks, changes)

    val linearLocationOne = adjustedLinearLocations.find(_.id == 1L).get

    linearLocationOne.startMValue should be (0.0)
    linearLocationOne.endMValue should be (7.5)
    linearLocationOne.sideCode should be (SideCode.TowardsDigitizing)

    val linearLocationTwo = adjustedLinearLocations.find(_.id == 2L).get

    linearLocationTwo.startMValue should be (7.5)
    linearLocationTwo.endMValue should be (15.0)
    linearLocationOne.sideCode should be (SideCode.TowardsDigitizing)

    changeSet.droppedSegmentIds.size should be (2)
    changeSet.droppedSegmentIds should contain only (1,2)
    changeSet.newLinearLocations should contain only (linearLocationOne, linearLocationTwo)
  }

  test("Test applyChange When the road link change is older Then any change should be applied") {

    val linearLocations = Seq(
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 1L),
      dummyLinearLocation(id = 2L, roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0, vvhTimestamp = 1L),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 1L),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 1L)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L, Seq(0.0, 10.0, 25.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L, Seq(0.0, 10.0), NormalLinkInterface)
    )

    val changes = Seq(
      dummyChangeInfo(ChangeType.LengthenedCommonPart, oldId = 123L, newId = 123L, oldStartMeasure = 0.0, oldEndMeasure = 20.0, newStartMeasure = 0.0, newEndMeasure = 20.0, vvhTimeStamp = 0L),
      dummyNewChangeInfo(ChangeType.LengthenedNewPart, newId = 123L, newStartMeasure = 20.0, newEndMeasure = 25.0, vvhTimeStamp = 0L)
    )

    val (adjustedLinearLocations, changeSet) = ApplyChangeInfoProcess.applyChanges(linearLocations, roadLinks, changes)

    adjustedLinearLocations.sortBy(_.id) should be (linearLocations)
    changeSet should be (ChangeSet(Set.empty, Seq.empty, Seq.empty, Seq.empty))
  }

  test("Test applyChange When there at least one new road link change not supported Then any change should be applied for that road link") {

    val linearLocations = Seq(
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 1L),
      dummyLinearLocation(id = 2L, roadwayNumber = 1L, orderNumber = 2L, linkId = 123L, startMValue = 10.0, endMValue = 20.0, vvhTimestamp = 1L),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 1L),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 1L)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L, Seq(0.0, 10.0, 25.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L, Seq(0.0, 10.0), NormalLinkInterface)
    )

    val changes = Seq(
      dummyChangeInfo(ChangeType.LengthenedCommonPart, oldId = 123L, newId = 123L, oldStartMeasure = 0.0, oldEndMeasure = 20.0, newStartMeasure = 0.0, newEndMeasure = 20.0, vvhTimeStamp = 1L),
      dummyNewChangeInfo(ChangeType.LengthenedNewPart, newId = 123L, newStartMeasure = 20.0, newEndMeasure = 25.0, vvhTimeStamp = 1L),
      dummyNewChangeInfo(ChangeType.Removed, newId = 123L, newStartMeasure = 20.0, newEndMeasure = 25.0, vvhTimeStamp = 3L)
    )

    val (adjustedLinearLocations, changeSet) = ApplyChangeInfoProcess.applyChanges(linearLocations, roadLinks, changes)

    adjustedLinearLocations.sortBy(_.id) should be (linearLocations)
    changeSet should be (ChangeSet(Set.empty, Seq.empty, Seq.empty, Seq.empty))
  }

}
