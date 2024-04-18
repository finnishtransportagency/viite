package fi.vaylavirasto.viite.model

sealed trait SideCode {
  def value: Int
}
object SideCode {
  private val values = Set(TowardsDigitizing, AgainstDigitizing, Unknown)

  def apply(intValue: Int): SideCode = {
    values.find(_.value == intValue).getOrElse(Unknown)
  }

  def switch(sideCode: SideCode): SideCode = {
    sideCode match {
      case TowardsDigitizing => AgainstDigitizing
      case AgainstDigitizing => TowardsDigitizing
      case _ => sideCode
    }
  }

  case object TowardsDigitizing extends SideCode { def value = 2 }
  case object AgainstDigitizing extends SideCode { def value = 3 }
  case object Unknown           extends SideCode { def value = 9 }
}

