package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.NormalLinkInterface
import fi.liikennevirasto.digiroad2.asset.SideCode
import fi.liikennevirasto.digiroad2.client.kgv.ChangeType
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.Dummies._
import fi.liikennevirasto.viite.process.RoadAddressFiller.ChangeSet
import org.scalatest.{FunSuite, Matchers}

class ApplyChangeInfoProcessSpec extends FunSuite with Matchers {

  test("Test applyChange When the road link is lengthened at the start Then linear locations measure should be adjusted") {

    val linearLocations = Seq(
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 2L, roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L.toString, Seq(0.0, 10.0, 25.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L.toString, Seq(0.0, 10.0), NormalLinkInterface)
    )

    val changes = Seq(
      dummyChangeInfo(ChangeType.LengthenedCommonPart, oldId = 123L.toString, newId = 123L.toString, oldStartMeasure = 0.0, oldEndMeasure = 20.0, newStartMeasure = 5.0, newEndMeasure = 25.0, vvhTimeStamp = 1L),
      dummyNewChangeInfo(ChangeType.LengthenedNewPart, newId = 123L.toString, newStartMeasure = 0.0, newEndMeasure = 5.0, vvhTimeStamp = 1L)
    )

    val (locations, changeSet): (Seq[LinearLocation], ChangeSet) = ApplyChangeInfoProcess.applyChanges(linearLocations, roadLinks, changes)
    val adjustedLinearLocations = locations
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
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 2L, roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L.toString, Seq(0.0, 10.0, 25.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L.toString, Seq(0.0, 10.0), NormalLinkInterface)
    )

    val changes = Seq(
      dummyChangeInfo(ChangeType.LengthenedCommonPart, oldId = 123L.toString, newId = 123L.toString, oldStartMeasure = 0.0, oldEndMeasure = 20.0, newStartMeasure = 0.0, newEndMeasure = 20.0, vvhTimeStamp = 1L),
      dummyNewChangeInfo(ChangeType.LengthenedNewPart, newId = 123L.toString, newStartMeasure = 20.0, newEndMeasure = 25.0, vvhTimeStamp = 1L)
    )

    val (locations, changeSet): (Seq[LinearLocation], ChangeSet) = ApplyChangeInfoProcess.applyChanges(linearLocations, roadLinks, changes)
    val adjustedLinearLocations = locations
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
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 2L, roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 126L.toString, Seq(0.0, 12.0), NormalLinkInterface),
      dummyRoadLink(linkId = 127L.toString, Seq(12.0, 20.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L.toString, Seq(0.0, 10.0), NormalLinkInterface)
    )

    val changes = Seq(
      dummyChangeInfo(ChangeType.DividedModifiedPart, oldId = 123L.toString, newId = 126L.toString, oldStartMeasure = 0.0, oldEndMeasure = 12.0, newStartMeasure = 0.0, newEndMeasure = 12.0, vvhTimeStamp = 1L),
      dummyChangeInfo(ChangeType.DividedNewPart, oldId = 123L.toString, newId = 127L.toString, oldStartMeasure = 12.0, oldEndMeasure = 20.0, newStartMeasure = 0.0, newEndMeasure = 8.0, vvhTimeStamp = 1L)
    )

    val (locations, changeSet): (Seq[LinearLocation], ChangeSet) = ApplyChangeInfoProcess.applyChanges(linearLocations, roadLinks, changes)
    val adjustedLinearLocations = locations

    val newLinearLocations = adjustedLinearLocations.filter(_.id == -1000).sortBy(_.orderNumber).toArray

    val linearLocationOne = newLinearLocations(0)

    linearLocationOne.startMValue should be (0.0)
    linearLocationOne.endMValue should be (10.0)
    linearLocationOne.linkId should be (126L.toString)
    linearLocationOne.sideCode should be (SideCode.TowardsDigitizing)

    val linearLocationTwo = newLinearLocations(1)

    linearLocationTwo.startMValue should be (10.0)
    linearLocationTwo.endMValue should be (12.0)
    linearLocationTwo.linkId should be (126L.toString)
    linearLocationTwo.sideCode should be (SideCode.TowardsDigitizing)

    val linearLocationThree = newLinearLocations(2)

    linearLocationThree.startMValue should be (0.0)
    linearLocationThree.endMValue should be (8.0)
    linearLocationThree.linkId should be (127L.toString)
    linearLocationThree.sideCode should be (SideCode.TowardsDigitizing)

    every (newLinearLocations.map(_.orderNumber).toList) should (be >= 1.0 and be < 3.0)

    changeSet.droppedSegmentIds.size should be (2)
    changeSet.droppedSegmentIds should contain only (1,2)
    changeSet.newLinearLocations should contain only (linearLocationOne, linearLocationTwo, linearLocationThree)

  }

  test("Test applyChange When the road link is divided in first linear location Then linear locations measure should be adjusted and also divided") {

    val linearLocations = Seq(
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 2L, roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 126L.toString, Seq(0.0, 8.0), NormalLinkInterface),
      dummyRoadLink(linkId = 127L.toString, Seq(8.0, 20.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L.toString, Seq(0.0, 10.0), NormalLinkInterface)
    )

    val changes = Seq(
      dummyChangeInfo(ChangeType.DividedModifiedPart, oldId = 123L.toString, newId = 126L.toString, oldStartMeasure = 0.0, oldEndMeasure = 8.0, newStartMeasure = 0.0, newEndMeasure = 8.0, vvhTimeStamp = 1L),
      dummyChangeInfo(ChangeType.DividedNewPart, oldId = 123L.toString, newId = 127L.toString, oldStartMeasure = 8.0, oldEndMeasure = 20.0, newStartMeasure = 0.0, newEndMeasure = 12.0, vvhTimeStamp = 1L)
    )

    val (locations, changeSet): (Seq[LinearLocation], ChangeSet) = ApplyChangeInfoProcess.applyChanges(linearLocations, roadLinks, changes)
    val adjustedLinearLocations = locations

    val newLinearLocations = adjustedLinearLocations.filter(_.id == -1000).sortBy(_.orderNumber).toArray

    val linearLocationOne = newLinearLocations(0)

    linearLocationOne.startMValue should be (0.0)
    linearLocationOne.endMValue should be (8.0)
    linearLocationOne.linkId should be (126L.toString)
    linearLocationOne.sideCode should be (SideCode.TowardsDigitizing)

    val linearLocationTwo = newLinearLocations(1)

    linearLocationTwo.startMValue should be (0.0)
    linearLocationTwo.endMValue should be (2.0)
    linearLocationTwo.linkId should be (127L.toString)
    linearLocationTwo.sideCode should be (SideCode.TowardsDigitizing)

    val linearLocationThree = newLinearLocations(2)

    linearLocationThree.startMValue should be (2.0)
    linearLocationThree.endMValue should be (12.0)
    linearLocationThree.linkId should be (127L.toString)
    linearLocationThree.sideCode should be (SideCode.TowardsDigitizing)

    every (newLinearLocations.map(_.orderNumber).toList) should (be >= 1.0 and be < 3.0)

    changeSet.droppedSegmentIds.size should be (2)
    changeSet.droppedSegmentIds should contain only (1,2)
    changeSet.newLinearLocations should contain only (linearLocationOne, linearLocationTwo, linearLocationThree)

  }

  test("Test applyChange When the road link is combined Then linear locations measure should be adjusted and also combined") {

    val linearLocations = Seq(
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 2L, roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L.toString, Seq(0.0, 10.0, 20.0), NormalLinkInterface),
      dummyRoadLink(linkId = 127L.toString, Seq(20.0, 30.0, 40.0), NormalLinkInterface)
    )

    val changes = Seq(
      dummyChangeInfo(ChangeType.CombinedModifiedPart, oldId = 124L.toString, newId = 127L.toString, oldStartMeasure = 0.0, oldEndMeasure = 10.0, newStartMeasure = 0.0, newEndMeasure = 10.0, vvhTimeStamp = 1L),
      dummyChangeInfo(ChangeType.CombinedRemovedPart, oldId = 125L.toString, newId = 127L.toString, oldStartMeasure = 0.0, oldEndMeasure = 10.0, newStartMeasure = 10.0, newEndMeasure = 20.0, vvhTimeStamp = 1L)
    )

    val (locations, changeSet): (Seq[LinearLocation], ChangeSet) = ApplyChangeInfoProcess.applyChanges(linearLocations, roadLinks, changes)
    val adjustedLinearLocations = locations

    val newLinearLocations = adjustedLinearLocations.filter(_.id == -1000).sortBy(_.orderNumber).toArray

    val linearLocationOne = newLinearLocations(0)

    linearLocationOne.startMValue should be (0.0)
    linearLocationOne.endMValue should be (10.0)
    linearLocationOne.linkId should be (127L.toString)
    linearLocationOne.sideCode should be (SideCode.TowardsDigitizing)

    val linearLocationTwo = newLinearLocations(1)

    linearLocationTwo.startMValue should be (10.0)
    linearLocationTwo.endMValue should be (20.0)
    linearLocationTwo.linkId should be (127L.toString)
    linearLocationTwo.sideCode should be (SideCode.TowardsDigitizing)

    every (newLinearLocations.map(_.orderNumber).toList) should (be >= 1.0 and be < 5.0)

    changeSet.droppedSegmentIds.size should be (2)
    changeSet.droppedSegmentIds should contain only (4,3)
    changeSet.newLinearLocations should contain only (linearLocationOne, linearLocationTwo)

  }

  test("Test applyChange When the road link is shortened at the start Then linear locations measure should be adjusted") {

    val linearLocations = Seq(
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 2L, roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L.toString, Seq(0.0, 5.0, 15.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L.toString, Seq(0.0, 10.0), NormalLinkInterface)
    )

    val changes = Seq(
      dummyChangeInfo(ChangeType.ShortenedCommonPart, oldId = 123L.toString, newId = 123L.toString, oldStartMeasure = 5.0, oldEndMeasure = 20.0, newStartMeasure = 0.0, newEndMeasure = 15.0, vvhTimeStamp = 1L),
      dummyOldChangeInfo(ChangeType.ShortenedRemovedPart, oldId = 123L.toString, oldStartMeasure = 0.0, oldEndMeasure = 5.0, vvhTimeStamp = 1L)
    )

    val (locations, changeSet): (Seq[LinearLocation], ChangeSet) = ApplyChangeInfoProcess.applyChanges(linearLocations, roadLinks, changes)
    val adjustedLinearLocations = locations

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
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 30.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L.toString, Seq(0.0, 10.0, 20.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L.toString, Seq(0.0, 10.0), NormalLinkInterface)
    )

    val changes = Seq(
      dummyChangeInfo(ChangeType.ShortenedCommonPart, oldId = 123L.toString, newId = 123L.toString, oldStartMeasure = 10.0, oldEndMeasure = 30.0, newStartMeasure = 0.0, newEndMeasure = 20.0, vvhTimeStamp = 1L),
      dummyOldChangeInfo(ChangeType.ShortenedRemovedPart, oldId = 123L.toString, oldStartMeasure = 0.0, oldEndMeasure = 10.0, vvhTimeStamp = 1L)
    )

    val (locations, changeSet): (Seq[LinearLocation], ChangeSet) = ApplyChangeInfoProcess.applyChanges(linearLocations, roadLinks, changes)
    val adjustedLinearLocations = locations

    adjustedLinearLocations.sortBy(_.id) should be (linearLocations)
    changeSet should be (ChangeSet(Set.empty, Seq.empty, Seq.empty))
  }

  test("Test applyChange When the road link is shortened at the end Then linear locations measure should be adjusted") {

    val linearLocations = Seq(
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 2L, roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 0L)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L.toString, Seq(0.0, 5.0, 15.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L.toString, Seq(0.0, 10.0), NormalLinkInterface)
    )

    val changes = Seq(
      dummyChangeInfo(ChangeType.ShortenedCommonPart, oldId = 123L.toString, newId = 123L.toString, oldStartMeasure = 0.0, oldEndMeasure = 15.0, newStartMeasure = 0.0, newEndMeasure = 15.0, vvhTimeStamp = 1L),
      dummyOldChangeInfo(ChangeType.ShortenedRemovedPart, oldId = 123L.toString, oldStartMeasure = 15.0, oldEndMeasure = 20.0, vvhTimeStamp = 1L)
    )

    val (locations, changeSet): (Seq[LinearLocation], ChangeSet) = ApplyChangeInfoProcess.applyChanges(linearLocations, roadLinks, changes)
    val adjustedLinearLocations = locations

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
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 1L),
      dummyLinearLocation(id = 2L, roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0, vvhTimestamp = 1L),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 1L),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 1L)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L.toString, Seq(0.0, 10.0, 25.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L.toString, Seq(0.0, 10.0), NormalLinkInterface)
    )

    val changes = Seq(
      dummyChangeInfo(ChangeType.LengthenedCommonPart, oldId = 123L.toString, newId = 123L.toString, oldStartMeasure = 0.0, oldEndMeasure = 20.0, newStartMeasure = 0.0, newEndMeasure = 20.0, vvhTimeStamp = 0L),
      dummyNewChangeInfo(ChangeType.LengthenedNewPart, newId = 123L.toString, newStartMeasure = 20.0, newEndMeasure = 25.0, vvhTimeStamp = 0L)
    )

    val (locations, changeSet): (Seq[LinearLocation], ChangeSet) = ApplyChangeInfoProcess.applyChanges(linearLocations, roadLinks, changes)
    val adjustedLinearLocations = locations

    adjustedLinearLocations.sortBy(_.id) should be (linearLocations)
    changeSet should be (ChangeSet(Set.empty, Seq.empty, Seq.empty))
  }

  test("Test applyChange When there at least one new road link change not supported Then any change should be applied for that road link") {

    val linearLocations = Seq(
      dummyLinearLocation(id = 1L, roadwayNumber = 1L, orderNumber = 1L, linkId = 123L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 1L),
      dummyLinearLocation(id = 2L, roadwayNumber = 1L, orderNumber = 2L, linkId = 123L.toString, startMValue = 10.0, endMValue = 20.0, vvhTimestamp = 1L),
      dummyLinearLocation(id = 3L, roadwayNumber = 1L, orderNumber = 3L, linkId = 124L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 1L),
      dummyLinearLocation(id = 4L, roadwayNumber = 1L, orderNumber = 4L, linkId = 125L.toString, startMValue = 0.0, endMValue = 10.0, vvhTimestamp = 1L)
    )

    val roadLinks = Seq(
      dummyRoadLink(linkId = 123L.toString, Seq(0.0, 10.0, 25.0), NormalLinkInterface),
      dummyRoadLink(linkId = 124L.toString, Seq(0.0, 10.0), NormalLinkInterface)
    )

    val changes = Seq(
      dummyChangeInfo(ChangeType.LengthenedCommonPart, oldId = 123L.toString, newId = 123L.toString, oldStartMeasure = 0.0, oldEndMeasure = 20.0, newStartMeasure = 0.0, newEndMeasure = 20.0, vvhTimeStamp = 1L),
      dummyNewChangeInfo(ChangeType.LengthenedNewPart, newId = 123L.toString, newStartMeasure = 20.0, newEndMeasure = 25.0, vvhTimeStamp = 1L),
      dummyNewChangeInfo(ChangeType.Removed, newId = 123L.toString, newStartMeasure = 20.0, newEndMeasure = 25.0, vvhTimeStamp = 3L)
    )

    val (locations, changeSet): (Seq[LinearLocation], ChangeSet) = ApplyChangeInfoProcess.applyChanges(linearLocations, roadLinks, changes)
    val adjustedLinearLocations = locations

    adjustedLinearLocations.sortBy(_.id) should be (linearLocations)
    changeSet should be (ChangeSet(Set.empty, Seq.empty, Seq.empty))
  }

}
