package fi.vaylavirasto.viite.model

import fi.vaylavirasto.viite.util.ViiteException

import scala.math.Ordered.orderingToOrdered // for compare function

/**
 * RoadPart contains road number, and part number information of a road, providing basic validation
 * for the numbers' correctness, and basic boolean functions to easily check the state of the RoadPart.
 *
 * If a RoadPart has either or both its values 0, that is, e.g. RoadPart(0,0), the RoadPart is said to be unaddressed, and/or invalid.
 *
 * @throws IllegalArgumentException at construction, if the roadNumber, or partNumber are out of acceptable values.
 */
case class RoadPart (roadNumber: Long, partNumber: Long) extends Ordered[RoadPart]
{

  val maxRoadNumber = 99999
  val maxPartNumber = 999

  // throws IllegalArgumentException if requirements not met
  try {
    require((roadNumber>=0 & roadNumber <= maxRoadNumber),      s"Road number must be between (0-)1-$maxRoadNumber")
    require((partNumber>=0 & partNumber <= maxPartNumber), s"Road part number must be between (0-)1-$maxPartNumber")
  } catch {
    case iae: IllegalArgumentException => throw new ViiteException(iae.getMessage)
  }
  /** Returns the RoadPart in the format "roadNumber/partNumber", e.g. "3575/1".
    * Overrides the very basic java.Object.toString.
    * @override [[java.object.String]] */
  override def toString : String = {  s"$roadNumber/$partNumber"  }

  /** Returns true, if the compared RoadParts have same road number, and both RoadParts are valid. Else false. */
  def isAtSameRoadAs  (other: RoadPart): Boolean = {  this.roadNumber == other.roadNumber && this.isValid && other.isValid }
  /** Returns true, if the compared RoadParts have same road number, and this RoadPart has smaller part number than <i>other</i>, and both RoadParts are valid. Else false. */
  def isBefore        (other: RoadPart): Boolean = {  this.roadNumber == other.roadNumber && this.partNumber < other.partNumber && this.isValid && other.isValid  }
  /** Returns true, if the compared RoadParts have same road number, and this RoadPart has bigger  part number than <i>other</i>, and both RoadParts are valid. Else false. */
  def isAfter         (other: RoadPart): Boolean = {  this.roadNumber == other.roadNumber && this.partNumber > other.partNumber && this.isValid && other.isValid  }

  /** Convenience function. Returns true, if this RoadPart is invalid (see [[RoadPart.isInvalid]]). Else false. */
  def isUnaddressed: Boolean = {  this.isInvalid  }
  /** A valid road part is one with positive road number, and road part number.
    * Returns true, if this RoadPart has greater than zero both road number, and road part number. Else false. */
  def isValid:       Boolean = {  this.roadNumber >  0 && this.partNumber >  0  }
  /** An invalid road part has at least one unacceptable value.
    * Returns true, if this RoadPart has at most zero road number, or road part number (or both). Else false. */
  def isInvalid:     Boolean = {  this.roadNumber <= 0 || this.partNumber <= 0  }

  /** Provides [[Ordered]] extension, thus offering comparison operators ==, <, >, <=, and >=.
    * @implements [[Ordered.compare]] */
  override def compare(that: RoadPart): Int = (this.roadNumber, this.partNumber) compare (that.roadNumber, that.partNumber)
}

