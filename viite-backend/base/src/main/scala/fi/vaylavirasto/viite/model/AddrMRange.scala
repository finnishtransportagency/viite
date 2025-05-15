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
  /** Convenience function. Synonym of isInvalid. Returns true, if this AddrMRange is invalid (see [[AddrMRange.isInvalid]]). Else false. */
  def isUndefined: Boolean = {  this.isInvalid  }

  /** Returns true, if this AddrMRange has 0 as start, and >0 as end (that is, it is a valid addrMRange). */
  def isRoadPartStart:Boolean = {  this.start == 0 && this.end >  0  }


  // ----------------------------------- Connectivity checks -----------------------------------
  /** Returns true, if the compared AddrMRanges have the same start, and end values, and both are valid ranges. Else false.
   * @throws ViiteException if either AddrMRange isUndefined.
   */
  def isSameAs(other: AddrMRange): Boolean = {
    if(!bothAreValid(this,other))
      throw new ViiteException(s"Cannot detect sameness of undefined address range (between $this, $other). ")
    this.start == other.start && this.end  == other.end && bothAreValid(this,other)
  }

  /** Returns true, if other AddrMRange fully fits within this AddrMRange, (equal ending points allowed,) and both are valid ranges. Else false.
   * @throws ViiteException if either AddrMRange isUndefined.
   */
  def contains(other: AddrMRange): Boolean = {
    if(!bothAreValid(this,other))
      throw new ViiteException(s"Cannot detect containment of undefined address range (between $this, $other). ")
    this.start <= other.start && other.end <= this.end && bothAreValid(this,other)
  }

  /** Returns true, if this AddrMRange has more than single point in common with <i>other</i>, and both are valid ranges. Else false.
   * @throws ViiteException if either AddrMRange isUndefined.
   */
  def overlaps(other: AddrMRange): Boolean = {
    if(!bothAreValid(this,other))
      throw new ViiteException(s"Cannot detect overlapping of undefined address range (between $this, $other). ")
    this.start < other.end && this.end > other.start && bothAreValid(this,other)
  }

  /** Returns true, if <i>other</i> is right after <i>this</i>, i.e. this.end == other.start, and both are valid ranges. Else false. */
  def continuesTo(other: AddrMRange): Boolean = {   this.end == other.start && bothAreValid(this,other)  }
  /** Returns true, if <i>other</i> is right before <i>this</i>, i.e. this.start == other.end, and both are valid ranges. Else false. */
  def continuesFrom(other: AddrMRange): Boolean = {   this.start == other.end && bothAreValid(this,other)  }
  /** Returns true, if <i>other</i> is right before or after <i>this</i>, i.e. this.end == other.start, or this.end == other.start, and both are valid ranges. Else false. */
  def isAdjacentTo      (other: AddrMRange): Boolean = {  (this.end == other.start || this.start == other.end) && bothAreValid(this,other)  }

  // ----------------------------------- Comparisons to a single Long address value -----------------------------------
  def startsAt        (addrM: Long): Boolean = {  this.start == addrM                      && this.isValid  }
  def endsAt          (addrM: Long): Boolean = {                         this.end == addrM && this.isValid  }

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
 *
   * @param amountM How many road address meters is this AddrMRange moved.
   * @throws ViiteException if this AddrMRange isUndefined, or start
   *                        or end <!-- TODO "or end" can be removed, when start < end requirement at AddrMRange construction can be set on its place -->
   *                        would get negative when moved. */
  def move(amountM: Long): AddrMRange = {
    if(this.isUndefined)
      throw ViiteException("Cannot move an undefined address.")
    if(this.start+amountM<0)
      throw ViiteException(s"Cannot move address range $this that much. Moving it $amountM would cause start address to be negative.")
    if(this.end+amountM<0)    // TODO the end+amountM<0 test seems silly after start already been tested, but must be here as long as start < end requirement of the AddrMRange cannot be put into work
      throw ViiteException(s"Cannot move address range $this that much. Moving it $amountM would cause end address to be negative.")
    if(this.end+amountM>maxAddrM)
      throw ViiteException(s"Cannot move address range $this that much. Moving it $amountM would cause start address to be insanely big.")
    if(this.start+amountM>maxAddrM)    // TODO the start+amountM>maxAddrM test seems silly after end already been tested, but must be here as long as start < end requirement of the AddrMRange cannot be put into work
      throw ViiteException(s"Cannot move address range $this that much. Moving it $amountM would cause start address to be insanely big.")

    AddrMRange(start + amountM, end + amountM)
  }

  /** Returns an AddrMRange flipped within reference range AdddrMRange(0,<i>flipLength</i>),
   * if the resulting AddrMRange would be valid. Otherwise, throws ViiteException.
   *
   * @param flipLength The intended address length to use as the flipping point. Most often
   *                   this is length of the road part this addrMRange is part of.
   * <pre>
   * Example: Flip AddrMRange(50,200) as it was part of
   *          AddrMRange(0,300): get AddrMRange(100,250).
   *                                      50                      200
   *   orig                        0       &gt;-------+-------+-------&gt;              300
   *   ref (the whole road part)   &gt;-------+-------+-------+-------+-------+-------&gt;
   *
   *   ref (flipped road part)     &lt;-------+-------+-------+-------+-------+-------&lt;
   *   flipped                    300      &lt;-------+-------+-------&lt;               0
   *                                      250                     100
   * </pre>
   *
   * @throws ViiteException if this AdddrMRange does not fit within AdddrMRange(0,<i>flipLength</i>).
   * @throws ViiteException if this AddrMRange isUndefined, flipLength is non-positive, or start
   *                        or end <!-- TODO "or end" can be removed, when start < end requirement at AddrMRange construction can be set on its place -->
   *                        would get negative when moved. */
  def flipRelativeTo(flipLength: Long): AddrMRange = {
    if(this.isUndefined)
      throw ViiteException("Cannot flip an undefined address.")
    if(flipLength<=0)
      throw ViiteException("Cannot flip over a non-positive end point.")
    if(flipLength-this.end<0)
      throw ViiteException(s"Cannot flip address range $this with respect to $flipLength. Flipping would cause the start address to be negative.")
    if(flipLength-this.start<0)    // TODO the end+amountM<0 test seems silly after start already been tested, but must be here as long as start < end requirement of the AddrMRange cannot be put into work
      throw ViiteException(s"Cannot flip address range $this with respect to $flipLength. Flipping would cause the end address to be negative.")

    AddrMRange(flipLength-end, flipLength-start)
  }

//  /** Provides [[Ordered]] extension, thus offering comparison operators ==, <, >, <=, and >=.
//    * @implements [[Ordered.compare]] */
//  override def compare(that: AddrMRange): Int = (this.startAddrM, this.endAddrM) compare (that.startAddrM, that.endAddrM)
}

