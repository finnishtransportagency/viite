package fi.liikennevirasto.digiroad2.linearasset

case class ValidityPeriod(val startHour: Int, val endHour: Int, val days: ValidityPeriodDayOfWeek,
                          val startMinute: Int = 0, val endMinute: Int = 0) {
  def and(b: ValidityPeriod): Option[ValidityPeriod] = {
    if (overlaps(b)) {
      Some(ValidityPeriod(math.max(startHour, b.startHour), math.min(endHour, b.endHour), ValidityPeriodDayOfWeek.moreSpecific(days, b.days), math.min(startMinute, b.startMinute), math.min(endMinute, b.endMinute)))
    } else {
      None
    }
  }
  def duration(): Int = {
    val startHourAndMinutes: Double = (startMinute / 60.0) + startHour
    val endHourAndMinutes: Double = (endMinute / 60.0) + endHour

    if (endHourAndMinutes > startHourAndMinutes) {
      Math.ceil(endHourAndMinutes - startHourAndMinutes).toInt
    } else {
      Math.ceil(24 - startHourAndMinutes + endHourAndMinutes).toInt
    }
  }

  def preciseDuration(): (Int, Int) = {
    val startTotalMinutes = startMinute + (startHour * 60)
    val endTotalMinutes = endMinute + (endHour * 60)

    if (endTotalMinutes > startTotalMinutes) {
      val duration = endTotalMinutes - startTotalMinutes
      ((duration / 60), duration % 60)
    } else {
      val duration = 1440 - startTotalMinutes + endTotalMinutes
      ((duration / 60), duration % 60)
    }
  }


  private def overlaps(b: ValidityPeriod): Boolean = {
    ValidityPeriodDayOfWeek.overlap(days, b.days) && hoursOverlap(b)
  }

  private def hoursOverlap(b: ValidityPeriod): Boolean = {
    val startHourAndMinutes = (startMinute / 60.0) + startHour
    val endHourAndMinutes = (endMinute / 60.0) + endHour
    val startHourAndMinutesB = (b.startMinute / 60.0) + b.startHour
    val endHourAndMinutesB = (b.endMinute / 60.0) + b.endHour

    liesInBetween(startHourAndMinutes, (startHourAndMinutesB, endHourAndMinutesB)) ||
      liesInBetween(startHourAndMinutesB, (startHourAndMinutes, endHourAndMinutes))
  }

  private def liesInBetween(hour: Double, interval: (Double, Double)): Boolean = {
    hour >= interval._1 && hour <= interval._2
  }
}

sealed trait ValidityPeriodDayOfWeek extends Equals { def value: Int }
object ValidityPeriodDayOfWeek {
  def apply(value: Int) = Seq(Weekday, Saturday, Sunday).find(_.value == value).getOrElse(Unknown)
  def apply(value: String) = value match {
    case "Sunday" => Sunday
    case "Weekday" => Weekday
    case "Saturday" => Saturday
    case _ => Unknown
  }
  def fromTimeDomainValue(value: Int) = value match {
    case 1 => Sunday
    case 2 => Weekday
    case 7 => Saturday
    case _ => Unknown
  }
  def moreSpecific: PartialFunction[(ValidityPeriodDayOfWeek, ValidityPeriodDayOfWeek), ValidityPeriodDayOfWeek] = {
    case (Unknown, d) => d
    case (d, Unknown) => d
    case (d, Weekday) => d
    case (Weekday, d) => d
    case (a, b) if a == b => a
    case (_, _) => Unknown
  }
  def overlap: PartialFunction[(ValidityPeriodDayOfWeek, ValidityPeriodDayOfWeek), Boolean] = {
    case (Unknown, _) => true
    case (_, Unknown) => true
    case (a, b) => a == b
  }

  case object Weekday extends ValidityPeriodDayOfWeek { val value = 1 }
  case object Saturday extends ValidityPeriodDayOfWeek { val value = 2 }
  case object Sunday extends ValidityPeriodDayOfWeek { val value = 3 }
  case object Unknown extends ValidityPeriodDayOfWeek { val value = 99 }
}
