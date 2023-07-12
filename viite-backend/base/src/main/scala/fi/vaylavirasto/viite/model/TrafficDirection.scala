package fi.vaylavirasto.viite.model

sealed trait TrafficDirection {
  def value: Int
}

object TrafficDirection {
  private val values = Set(BothDirections, AgainstDigitizing, TowardsDigitizing, UnknownDirection)

  def apply(intValue: Int): TrafficDirection = {
    values.find(_.value == intValue).getOrElse(UnknownDirection)
  }

  def apply(optionalValue: Option[Int]): TrafficDirection = {
    optionalValue.map { value => values.find(_.value == value).getOrElse(UnknownDirection) }.getOrElse(UnknownDirection)
  }

  def apply(stringValue: String): TrafficDirection = {
    values.find(_.toString == stringValue).getOrElse(UnknownDirection)
  }

  case object BothDirections    extends TrafficDirection { def value =  2 }
  case object AgainstDigitizing extends TrafficDirection { def value =  3 }
  case object TowardsDigitizing extends TrafficDirection { def value =  4 }
  case object UnknownDirection  extends TrafficDirection { def value = 99 }

}


