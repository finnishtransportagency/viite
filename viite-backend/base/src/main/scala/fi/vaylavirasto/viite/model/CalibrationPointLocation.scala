package fi.vaylavirasto.viite.model

trait CalibrationPointLocation {
  def value: Int
}

object CalibrationPointLocation {
  private val values = Set(StartOfLink, EndOfLink, Unknown)

  def apply(intValue: Int): CalibrationPointLocation = {
    values.find(_.value == intValue).getOrElse(Unknown)
  }

  def apply(pos: BeforeAfter): CalibrationPointLocation = {
    pos match {
      case BeforeAfter.After => StartOfLink
      case BeforeAfter.Before => EndOfLink
      case _ => Unknown
    }
  }

  case object StartOfLink extends CalibrationPointLocation {  def value =  0  }
  case object EndOfLink   extends CalibrationPointLocation {  def value =  1  }
  case object Unknown     extends CalibrationPointLocation {  def value = 99  }
}
