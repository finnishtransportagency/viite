package fi.vaylavirasto.viite.model

import fi.vaylavirasto.viite.util.ViiteException

/**
 * AddrMRange resembles start address to end address value range (meters) for a section of a road, in context of a [[RoadPart]].
 * AddrMRange class provides basic validation for the numbers' correctness, and basic boolean functions to easily check the state,
 * and compare AddrMRanges.
 *
 * If an AddrMRange has both its values 0, that is, AddrMRange(0,0), the AddrMRange is said to be undefined, and/or invalid.
 *
 * @throws ViiteException at construction, if the start, or end are out of acceptable values.
 */
case class AddrMRange (start: Long, end: Long)// extends Ordered[AddrMRange]
{

  val minAddrMZero = 0        // A metric address value for a road part cannot ever be negative.
  val maxAddrM     = 100*1000 // Semi-random upper limit. One road part will never be over 100km ever.

  // throws ViiteException if requirements not met
  try {
    require((start>=minAddrMZero && start <= maxAddrM), s"A start address must be between $minAddrMZero-$maxAddrM.")
    require((  end>=minAddrMZero &&   end <= maxAddrM), s"An  end address must be between $minAddrMZero-$maxAddrM.")
    require((  start < end || (start==0 &&  end==0)), s"A start address (now $start) must be smaller than end address (now $end), or range must be undefined (0-0).")
  } catch  {
    case e: IllegalArgumentException => throw ViiteException(e.getMessage)
  }

  /** Returns the AddrMRange in the format "start-end", e.g. "0-145".
    * Overrides the very basic java.Object.toString.
    * @override [[java.object.toString]] */
  override def toString : String = {  s"$start-$end"  }

  private def  bothAreValid(a: AddrMRange, b: AddrMRange): Boolean = { a.isValid && b.isValid }

  // --------------------------------------- Validity checks ---------------------------------------
  /** A valid address range is one with positive endAddrM. (Construction time checks ensure that start address is at least 0.)
    * Returns true, if this <i>end</i> is greater than zero. Else false. */
  def isValid:     Boolean = {  this.end > 0  }
  /** Returns true, if this AddrMRange has both <i>start</i>, and <i>end</i> zeroes. Else false. */
  def isInvalid:   Boolean = {  this.start == 0 && this.end == 0  }
  /** Convenience function. Returns true, if this AddrMRange is invalid (see [[AddrMRange.isInvalid]]). Else false. */
  def isUndefined: Boolean = {  this.isInvalid  }

//  /** Provides [[Ordered]] extension, thus offering comparison operators ==, <, >, <=, and >=.
//    * @implements [[Ordered.compare]] */
//  override def compare(that: AddrMRange): Int = (this.startAddrM, this.endAddrM) compare (that.startAddrM, that.endAddrM)
}

