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
    require((start >= minAddrMZero && start <= maxAddrM), s"A start address must be between $minAddrMZero-$maxAddrM.")
    require((  end >= minAddrMZero &&   end <= maxAddrM), s"An  end address must be between $minAddrMZero-$maxAddrM.")
    // Calculations so far require leaving this - I think very substantial - restriction out.
    //require((  start < end || (start==0 &&  end==0)), s"A start address (now $start) must be smaller than end address (now $end), or range must be undefined (0-0).")
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

  /** Returns true, if this AddrMRange has 0 as start, and >0 as end (that is, it is a valid addrMRange). */
  def isRoadPartStart:Boolean = {  this.start == 0 && this.end >  0  }

    // ---------------------------- Functions returning numeric values, or AddrMRange copies ----------------------------
  /** Returns the length of this AddrMRange for a valid AddrMRange.
    * @throws ViiteException if this AddrMRange isUndefined. */
  def length: Long = {
    if(this.isUndefined)
      throw new ViiteException(s"No length defined for undefined address range. ")
    this.end - this.start
  }
  /** Returns the length of this AddrMRange for a valid AddrMRange, or None else. */
  def lengthOption: Option[Long] = {
    if(this.isValid)
      Some(this.end - this.start)
    else
      None
  }

  /** Returns an AddrMRange moved by given <i>amountM</i>. Both ends get moved the given amount.
   * @param amountM How many road address metersis this AddrMRange moved.
   * @throws ViiteException if this AddrMRange isUndefined, or startAddress would get negative when moved. */
  def move(amountM: Long): AddrMRange = {
    if(this.isUndefined)
      throw ViiteException("Cannot move an undefined address.")
    if(this.start+amountM<0)
      throw ViiteException(s"Cannot move address range $this that much. Moving it $amountM would cause start address to be negative.")
    // TODO the end+amountM<0 test seems silly after start already been tested, but must be here as long as start < end requirement of the AddrMRange cannot be put into work
    if(this.end+amountM<0)
      throw ViiteException(s"Cannot move address range $this that much. Moving it $amountM would cause end address to be negative.")

    AddrMRange(start + amountM, end + amountM)
  }

  /** Returns an AddrMRange mirrored within reference range AdddrMRange(0,<i>mirrorEndAddrM</i>).
    *
    * Example: mirror AddrMRange(50,200) as it was part of AddrMRange(0,300): get AddrMRange(100,250).
    * <pre>
    *                       50                      200
    *   orig         0       &gt;-------+-------+-------&gt;             300
    *   ref          &gt;-------+-------+-------+-------+-------+-------&gt;
    *   ref          &lt;-------+-------+-------+-------+-------+-------&lt;
    *   mirrored    300      &lt;-------+-------+-------&lt;               0
    *                        250                  100
    * </pre>
    *
    * @throws ViiteException if this AdddrMRange does not fit within AdddrMRange(0,<i>mirrorEndAddrM</i>).
    * @throws ViiteException if this AddrMRange isUndefined, mirrorEndAddrM is non-positive, or start
    *                        or end <!-- TODO "or end" can be removed, when start < end requirement can be set on its place -->
    *                        would get negative when moved. */
  def mirrorBy(mirrorEndAddrM: Long): AddrMRange = {
    if(this.isUndefined)
      throw ViiteException("Cannot reverse move an undefined address.")
    if(mirrorEndAddrM<=0)
      throw ViiteException("Cannot reverse move over a non-positive end point.")
    if(mirrorEndAddrM-this.end<0)
      throw ViiteException(s"Cannot mirror address range $this with respect to $mirrorEndAddrM. Reversing would cause the mirrored start address to be negative.")
    // TODO the end+amountM<0 test seems silly after start already been tested, but must be here as long as start < end requirement of the AddrMRange cannot be put into work
    if(mirrorEndAddrM-this.start<0)
      throw ViiteException(s"Cannot mirror address range $this with respect to $mirrorEndAddrM. Reversing would cause the mirrored end address to be negative.")

    AddrMRange(mirrorEndAddrM-end, mirrorEndAddrM-start)
  }

//  /** Provides [[Ordered]] extension, thus offering comparison operators ==, <, >, <=, and >=.
//    * @implements [[Ordered.compare]] */
//  override def compare(that: AddrMRange): Int = (this.startAddrM, this.endAddrM) compare (that.startAddrM, that.endAddrM)
}

