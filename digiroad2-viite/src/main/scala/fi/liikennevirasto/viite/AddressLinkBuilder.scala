package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2
import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh.{FeatureClass, VVHRoadlink}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite.RoadType._
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.InvalidAddressDataException
import org.joda.time.{DateTime, DateTimeZone}
import org.joda.time.format.DateTimeFormat

trait AddressLinkBuilder {
  val RoadNumber = "ROADNUMBER"
  val RoadPartNumber = "ROADPARTNUMBER"
  val TrackCode = "TRACKCODE"
  val MunicipalityCode = "MUNICIPALITYCODE"
  val FinnishRoadName = "ROADNAME_FI"
  val SwedishRoadName = "ROADNAME_SE"
  val ComplementarySubType = 3
  val formatter = DateTimeFormat.forPattern("dd.MM.yyyy")

  lazy val municipalityMapping = if (OracleDatabase.isWithinSession)
    MunicipalityDAO.getMunicipalityMapping
  else
    OracleDatabase.withDynSession {
      MunicipalityDAO.getMunicipalityMapping
    }

  lazy val municipalityRoadMaintainerMapping = if (OracleDatabase.isWithinSession)
      MunicipalityDAO.getMunicipalityRoadMaintainers
    else
      OracleDatabase.withDynSession {
        MunicipalityDAO.getMunicipalityRoadMaintainers
      }

  def getRoadType(administrativeClass: AdministrativeClass, linkType: LinkType): RoadType = {
    (administrativeClass, linkType) match {
      case (State, CableFerry) => FerryRoad
      case (State, _) => PublicRoad
      case (Municipality, _) => MunicipalityStreetRoad
      case (Private, _) => PrivateRoadType
      case (_, _) => UnknownOwnerRoad
    }
  }

  def getLinkType(roadLink: VVHRoadlink): LinkType ={  //similar logic used in roadlinkservice
    roadLink.featureClass match {
      case FeatureClass.TractorRoad => TractorRoad
      case FeatureClass.DrivePath => SingleCarriageway
      case FeatureClass.CycleOrPedestrianPath => CycleOrPedestrianPath
      case _=> UnknownLinkType
    }
  }

  protected def toIntNumber(value: Any): Int = {
    try {
      value.asInstanceOf[String].toInt
    } catch {
      case e: Throwable => 0
    }
  }

  protected def toLongNumber(value: Any): Long = {
    try {
      value match {
        case b: BigInt => b.longValue()
        case _ => value.asInstanceOf[String].toLong
      }

    } catch {
      case e: Exception => 0L
    }
  }

  protected def toLongNumber(longOpt: Option[Long], valueOpt: Option[Any]): Long = {
    longOpt match {
      case Some(l) if l > 0 => l
      case _ => valueOpt.map(toLongNumber).getOrElse(0L)
    }
  }

  protected def extractModifiedAtVVH(attributes: Map[String, Any]): Option[String] = {
    def toLong(anyValue: Option[Any]) = {
      anyValue.map(_.asInstanceOf[BigInt].toLong)
    }
    def compareDateMillisOptions(a: Option[Long], b: Option[Long]): Option[Long] = {
      (a, b) match {
        case (Some(firstModifiedAt), Some(secondModifiedAt)) =>
          if (firstModifiedAt > secondModifiedAt)
            Some(firstModifiedAt)
          else
            Some(secondModifiedAt)
        case (Some(firstModifiedAt), None) => Some(firstModifiedAt)
        case (None, Some(secondModifiedAt)) => Some(secondModifiedAt)
        case (None, None) => None
      }
    }
    val toIso8601 = DateTimeFormat.forPattern("dd.MM.yyyy HH:mm:ss")
    val createdDate = toLong(attributes.get("CREATED_DATE"))
    val lastEditedDate = toLong(attributes.get("LAST_EDITED_DATE"))
    val geometryEditedDate = toLong(attributes.get("GEOMETRY_EDITED_DATE"))
    val endDate = toLong(attributes.get("END_DATE"))
    val latestDate = compareDateMillisOptions(lastEditedDate, geometryEditedDate)
    val withHistoryLatestDate = compareDateMillisOptions(latestDate, endDate)
    val timezone = DateTimeZone.forOffsetHours(0)
    val latestDateString = withHistoryLatestDate.orElse(createdDate).map(modifiedTime => new DateTime(modifiedTime, timezone)).map(toIso8601.print(_))
    latestDateString
  }


  /**
    * Fuses road addresses that do not contain history.
    * We will later have to refactor this so that we check if history is fusable and figure out rules for fusing.
    *
    * @param roadAddresses roadaddress seq
    * @return fused roadaddress seq
    */

  def fuseRoadAddressWithTransaction(roadAddresses: Seq[RoadAddress]): Seq[RoadAddress] = {
  OracleDatabase.withDynSession(
    fuseRoadAddress(roadAddresses)
  )
  }

  // TODO Implement in VIITE-1536
  def fuseRoadAddress(roadAddresses: Seq[RoadAddress]): Seq[RoadAddress] = {
    throw new NotImplementedError("The fuse process should use only linear location")
//    if (roadAddresses.size == 1) {
//      roadAddresses
//    } else {
//      val groupedRoadAddresses = roadAddresses.groupBy(record =>
//        (record.roadwayNumber, record.roadNumber, record.roadPartNumber, record.track.value, record.startDate, record.endDate, record.linkId, record.roadType, record.ely, record.terminated))
//
//      groupedRoadAddresses.flatMap { case (_, record) =>
//        fuseRoadAddressInGroup(record.sortBy(_.startAddrMValue))
//      }.toSeq
//    }
  }

  /**
    * Fuse recursively
    *
    * @param unprocessed road addresses ordered by the startMValue
    * @param ready recursive value
    * @return road addresses fused in reverse order
    */
  private def fuseRoadAddressInGroup(unprocessed: Seq[RoadAddress], ready: Seq[RoadAddress] = Nil): Seq[RoadAddress] = {
    if (ready.isEmpty)
      fuseRoadAddressInGroup(unprocessed.tail, Seq(unprocessed.head))
    else if (unprocessed.isEmpty)
      ready
    else
    {
      fuseRoadAddressInGroup(unprocessed.tail, fuseTwo(unprocessed.head, ready.head) ++ ready.tail)
    }
  }

  /**
    * Fusing Two RoadAddresses in One
    *
    * @param nextSegment
    * @param previousSegment
    * @return A sequence of RoadAddresses, 1 if possible to fuse, 2 if they are unfusable
    */
  private def fuseTwo(nextSegment: RoadAddress, previousSegment: RoadAddress): Seq[RoadAddress] = {
    throw new NotImplementedError("The fuse process should use only linear location")

//    // Test that at the road addresses lap at least partially or are connected (one extends another)
//    def addressConnected(nextSegment: RoadAddress, previousSegment: RoadAddress) = {
//      (nextSegment.startAddrMValue == previousSegment.endAddrMValue ||
//        previousSegment.startAddrMValue == nextSegment.endAddrMValue) ||
//        (nextSegment.startAddrMValue >= previousSegment.startAddrMValue &&
//          nextSegment.startAddrMValue <= previousSegment.endAddrMValue) ||
//        (previousSegment.startAddrMValue >= nextSegment.startAddrMValue &&
//          previousSegment.startAddrMValue <= nextSegment.endAddrMValue)
//    }
//
//    def getCalibrationPoints = {
//      /*  If the road addresses have a calibration in between check if one of the road addresses have
//          the same start and end addresses, if so the calibration point of the road address with same addresses
//          should be ignored
//       */
//      val cpNextSegment = nextSegment.calibrationPoints
//      val cpPreviousSegment = previousSegment.calibrationPoints
//      if (cpNextSegment._1.isDefined && cpPreviousSegment._2.isDefined) {
//        (
//          if (cpPreviousSegment._2.get.addressMValue != previousSegment.startAddrMValue) cpPreviousSegment else (cpPreviousSegment._1, None),
//          if (cpNextSegment._1.get.addressMValue != nextSegment.endAddrMValue) cpNextSegment else (None, cpNextSegment._2)
//        )
//      } else {
//        (cpNextSegment, cpPreviousSegment)
//      }
//    }
//
//    val (cpNext, cpPrevious) = getCalibrationPoints
//
//    def getMValues[T](leftMValue: T, rightMValue: T, op: (T, T) => T,
//                      getValue: (Option[CalibrationPoint], Option[CalibrationPoint]) => Option[T]) = {
//      /*  Take the value from Calibration Point if available or then use the given operation
//          Starting calibration point from previous segment if available or then it's the starting calibration point for
//          the next segment. If neither, use the min or max operation given as an argument.
//          Similarily for ending calibration points. Cases where the calibration point truly is between segments is
//          left unprocessed.
//       */
//      getValue(cpPrevious._1.orElse(cpNext._1), cpNext._2.orElse(cpPrevious._2)).getOrElse(op(leftMValue, rightMValue))
//    }
//
//    val tempId = fi.liikennevirasto.viite.NewRoadAddress
//
//    if (nextSegment.geometry.isEmpty) println(s"Empty geometry on linkId = ${nextSegment.linkId}, id = ${nextSegment.linkId}")
//    if (previousSegment.geometry.isEmpty) println(s"Empty geometry on linkId = ${previousSegment.linkId}, id = ${previousSegment.id}")
//
//    if (nextSegment.roadNumber == previousSegment.roadNumber &&
//      nextSegment.roadPartNumber == previousSegment.roadPartNumber &&
//      nextSegment.track.value == previousSegment.track.value &&
//      nextSegment.startDate == previousSegment.startDate &&
//      nextSegment.endDate == previousSegment.endDate &&
//      nextSegment.linkId == previousSegment.linkId &&
//      nextSegment.geometry.nonEmpty && previousSegment.geometry.nonEmpty && // Check if geometries are not empty
//      addressConnected(nextSegment, previousSegment) &&
//      !(cpNext._1.isDefined && cpPrevious._2.isDefined)) { // Check that the calibration point isn't between these segments
//
//      val startAddrMValue = getMValues[Long](nextSegment.startAddrMValue, previousSegment.startAddrMValue, Math.min, (cpp, _) => cpp.map(_.addressMValue))
//      val endAddrMValue = getMValues[Long](nextSegment.endAddrMValue, previousSegment.endAddrMValue, Math.max, (_, cpn) => cpn.map(_.addressMValue))
//      val startMValue = getMValues[Double](nextSegment.startMValue, previousSegment.startMValue, Math.min, (_, _) => None)
//      val endMValue = getMValues[Double](nextSegment.endMValue, previousSegment.endMValue, Math.max, (_, _) => None)
//
//      val calibrationPoints: (Option[CalibrationPoint], Option[CalibrationPoint]) = {
//        val left = Seq(cpNext._1, cpPrevious._1).flatten.sortBy(_.segmentMValue).headOption
//        val right = Seq(cpNext._2, cpPrevious._2).flatten.sortBy(_.segmentMValue).lastOption
//        (left.map(_.copy(segmentMValue = if (nextSegment.sideCode == SideCode.AgainstDigitizing) Math.abs(endMValue - startMValue) else startMValue)),
//          right.map(_.copy(segmentMValue = if (nextSegment.sideCode == SideCode.AgainstDigitizing) startMValue else Math.abs(endMValue - startMValue))))
//      }
//
//      if (nextSegment.sideCode.value != previousSegment.sideCode.value && GeometryUtils.geometryLength(previousSegment.geometry) != 0 && GeometryUtils.geometryLength(nextSegment.geometry) != 0)
//        throw new InvalidAddressDataException(s"Road Address ${nextSegment.id} and Road Address ${previousSegment.id} cannot have different side codes.")
//      val combinedGeometry: Seq[Point] = GeometryUtils.truncateGeometry3D(Seq(previousSegment.geometry.head, nextSegment.geometry.last), startMValue, endMValue)
//      val discontinuity = {
//        if (nextSegment.endMValue > previousSegment.endMValue) {
//          nextSegment.discontinuity
//        } else
//          previousSegment.discontinuity
//      }
//
//      Seq(RoadAddress(tempId, nextSegment.roadNumber, nextSegment.roadPartNumber, nextSegment.roadType, nextSegment.track,
//        discontinuity, startAddrMValue, endAddrMValue, nextSegment.startDate, nextSegment.endDate, nextSegment.createdBy,
//        nextSegment.linkId, startMValue, endMValue, nextSegment.sideCode, nextSegment.adjustedTimestamp,
//        calibrationPoints, floating = nextSegment.floating, combinedGeometry, nextSegment.linkGeomSource, nextSegment.ely, nextSegment.terminated,
//        nextSegment.roadwayNumber))
//
//    } else Seq(nextSegment, previousSegment)

  }
}
