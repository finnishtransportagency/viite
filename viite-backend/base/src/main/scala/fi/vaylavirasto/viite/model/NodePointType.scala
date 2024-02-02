package fi.vaylavirasto.viite.model

sealed trait NodePointType {
  def value: Int

  def displayValue: String
}

object NodePointType {
  val values: Set[NodePointType] = Set(RoadNodePoint, CalculatedNodePoint, UnknownNodePointType)

  def apply(intValue: Int): NodePointType = {
    values.find(_.value == intValue).getOrElse(UnknownNodePointType)
  }

  case object RoadNodePoint extends NodePointType {
    def value = 1

    def displayValue = "Tien solmukohta"
  }

  case object CalculatedNodePoint extends NodePointType {
    def value = 2

    def displayValue = "Laskettu solmukohta"
  }

  case object UnknownNodePointType extends NodePointType {
    def value = 99

    def displayValue = "Ei määritelty"
  }
}
