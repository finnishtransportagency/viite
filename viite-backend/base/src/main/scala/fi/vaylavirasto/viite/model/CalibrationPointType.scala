package fi.vaylavirasto.viite.model

trait CalibrationPointType extends Ordered[CalibrationPointType] {
  def value: Int

  def compare(that: CalibrationPointType): Int = {
    this.value - that.value
  }
}

object CalibrationPointType {
  val values = Set(NoCP, UserDefinedCP, JunctionPointCP, RoadAddressCP, UnknownCP)

  def apply(intValue: Int): CalibrationPointType = {
    values.find(_.value == intValue).getOrElse(UnknownCP)
  }

  case object NoCP            extends CalibrationPointType {  def value =  0  }
  case object UserDefinedCP   extends CalibrationPointType {  def value =  1  }
  case object JunctionPointCP extends CalibrationPointType {  def value =  2  }
  case object RoadAddressCP   extends CalibrationPointType {  def value =  3  }
  case object UnknownCP       extends CalibrationPointType {  def value = 99  }
}
