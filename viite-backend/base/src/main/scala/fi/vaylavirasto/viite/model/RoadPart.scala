package fi.vaylavirasto.viite.model

import scala.math.Ordered.orderingToOrdered // for compare function

/**
 * RoadPart contains road number, and part number information of a road, providing basic validation
 * for the numbers' correctness, and basic boolean boolean to easily check the state of the RoadPart.
 *
 * If a RoadPart has both its values 0, that is, RoadPart(0,0), the RoadPart is said to be unaddressed.
 *
 * @throws IllegalArgumentException at construction, if the roadNumber, or partNumber are out of acceptable values.
 */
case class RoadPart (roadNumber: Long, partNumber: Long) extends Ordered[RoadPart]
{

  val maxRoadNumber = 99999
  val maxPartNumber = 999

  // throws IllegalArgumentException if requirements not met
  require((roadNumber>=0 & roadNumber <= maxRoadNumber), "Road number must be between")
  require((partNumber>=0 & partNumber <= maxPartNumber), "Road number must be between")

  /** Returns the RoadPart in the format "roadNumber/partNumber", e.g. "3575/1".
    * Overrides the very basic java.Object.toString.
    * @override [[java.object.String]] */
  override def toString : String = {  s"$roadNumber/$partNumber"  }

  /** Returns true, if the compared RoadParts have same road number. Else false. */
  def isAtSameRoadThan(other: RoadPart): Boolean = {  this.roadNumber == other.roadNumber  }
  /** Returns true, if the compared RoadParts have same road number, and this RoadPArt has smaller part number than <i>other</i>. Else false. */
  def isBefore        (other: RoadPart): Boolean = {  this.roadNumber == other.roadNumber && this.partNumber < other.partNumber  }
  /** Returns true, if the compared RoadParts have same road number, and this RoadPArt has bigger  part number than <i>other</i>. Else false. */
  def isAfter         (other: RoadPart): Boolean = {  this.roadNumber == other.roadNumber && this.partNumber > other.partNumber  }

  /** Returns true, if this RoadPart has both road number, and road part number zeroes. Else false. */
  def isUnaddressed: Boolean = {  this.roadNumber == 0 && this.partNumber == 0  }
  /** A valid road part is one with positive road number, and road part number.
    * Returns true, if this RoadPart has both road number, and road part number greater than zeroes. Else false. */
  def isValid:       Boolean = {  this.roadNumber >  0 && this.partNumber >  0  }
  /** An invalid road part has at least one unacceptable value.
    * Returns true, if this RoadPart has either road number, or road part number (or both) at most zero. Else false. */
  def isInvalid:     Boolean = {  this.roadNumber <= 0 || this.partNumber <= 0  }

  def unzip = {
    (roadNumber, partNumber)
  }

  /** Provides [[Ordered]] extension, thus offering comparison operators ==, <, >, <=, and >=.
    * @implements [[Ordered.compare]] */
  override def compare(that: RoadPart): Int = (this.roadNumber, this.partNumber) compare (that.roadNumber, that.partNumber)
}

