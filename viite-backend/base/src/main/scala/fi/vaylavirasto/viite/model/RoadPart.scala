package fi.vaylavirasto.viite.model

import scala.math.Ordered.orderingToOrdered // for compare function

case class RoadPart (roadNumber: Long, partNumber: Long) extends Ordered[RoadPart]
{

  val maxRoadNumber = 99999
  val maxPartNumber = 999

  // throws IllegalArgumentException if requirements not met
  require((roadNumber>0 & roadNumber <= maxRoadNumber), "Road number must be between")
  require((partNumber>0 & partNumber <= maxPartNumber), "Road number must be between")

  override def toString : String = {
    s"$roadNumber / $partNumber"
  }

  def isAfter(other: RoadPart) = {
    this.roadNumber == other.roadNumber &&
    this.partNumber > other.partNumber
  }

  def unzip = {
    (roadNumber, partNumber)
  }

  def compare(that: RoadPart): Int = (this.roadNumber, this.partNumber) compare (that.roadNumber, that.partNumber)
}

