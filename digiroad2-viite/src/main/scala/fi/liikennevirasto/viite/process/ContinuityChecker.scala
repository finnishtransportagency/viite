package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.Discontinuity._
import fi.liikennevirasto.viite.dao.{RoadAddress, RoadwayDAO}

/**
  * Created by venholat on 15.9.2016.
  */

trait AddressError {
  val roadNumber: Long
  val roadPartNumber: Long
  val startMAddr: Option[Long]
  val endMAddr: Option[Long]
  val linkId: Option[Long]
}

case class MissingSegment(roadNumber: Long, roadPartNumber: Long, startMAddr: Option[Long], endMAddr: Option[Long], linkId: Option[Long]) extends AddressError
case class MissingLink(roadNumber: Long, roadPartNumber: Long, startMAddr: Option[Long], endMAddr: Option[Long], linkId: Option[Long]) extends AddressError

//TODO check the need of that continuity checker
class ContinuityChecker(roadLinkService: RoadLinkService) {

  def checkAddressesHaveNoGaps(addresses: Seq[RoadAddress]): Seq[MissingSegment] = {
    val addressMap = addresses.groupBy(_.startAddrMValue)
    val missingFirst = if (!addressMap.contains(0L)) {
      Seq(MissingSegment(addresses.head.roadNumber, addresses.head.roadPartNumber,
        Some(0), Some(addresses.map(_.startAddrMValue).min), None))
    } else {
      Seq()
    }
    missingFirst ++ addresses.filter(addressHasGapAfter(addressMap)).map(ra =>
      MissingSegment(ra.roadNumber, ra.roadPartNumber, Some(ra.startAddrMValue), None, None))
  }

  private def addressHasGapAfter(addressMap: Map[Long, Seq[RoadAddress]])(address: RoadAddress) = {
    // Test for end of road part
    def nothingAfter(address: RoadAddress, addressMap: Map[Long, Seq[RoadAddress]]) = {
      !addressMap.keySet.exists(_ >= address.endAddrMValue)
    }
    // Test if road part continues with combined tracks
    def combinedTrackFollows(address: RoadAddress, next: Seq[RoadAddress]) = {
      next.exists(na => na.track == Track.Combined)
    }
    // Test if same track continues (different tracks may have different start/end points)
    def sameTrackFollows(address: RoadAddress, next: Seq[RoadAddress]) = {
      next.exists(na => na.track == address.track)
    }
    // Test if combined track is now split into two => they both must be found
    def splitTrackFollows(address: RoadAddress, next: Seq[RoadAddress]) = {
        address.track == Track.Combined &&
          (next.exists(na => na.track == Track.LeftSide) &&
            next.exists(na => na.track == Track.RightSide))
    }

    val nextAddressItems = addressMap.getOrElse(address.endAddrMValue, Seq())
    !nothingAfter(address, addressMap) &&
      !(combinedTrackFollows(address, nextAddressItems) ||
        sameTrackFollows(address, nextAddressItems) ||
        splitTrackFollows(address, nextAddressItems))
  }

  private def checkLinksExist(addresses: Seq[RoadAddress]): Seq[MissingLink] = {
    Seq()
  }
}
