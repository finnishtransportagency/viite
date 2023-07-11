package fi.vaylavirasto.viite.model

sealed trait LinkType
{
  def value: Int
}

object LinkType {
  private val values = Set(
    Motorway,
    MultipleCarriageway,
    SingleCarriageway,
    Freeway,
    Roundabout,
    SlipRoad,
    RestArea,
    CycleOrPedestrianPath,
    PedestrianZone,
    ServiceOrEmergencyRoad,
    EnclosedTrafficArea,
    TractorRoad,
    MotorwayServiceAccess,
    CableFerry,
    UnknownLinkType)

  def apply(value: Int): LinkType = {
    values.find(_.value == value).getOrElse(UnknownLinkType)
  }
}

case object Motorway               extends LinkType { def value =  1 }
case object MultipleCarriageway    extends LinkType { def value =  2 }
case object SingleCarriageway      extends LinkType { def value =  3 }
case object Freeway                extends LinkType { def value =  4 }
case object Roundabout             extends LinkType { def value =  5 }
case object SlipRoad               extends LinkType { def value =  6 }
case object RestArea               extends LinkType { def value =  7 }
case object CycleOrPedestrianPath  extends LinkType { def value =  8 }
case object PedestrianZone         extends LinkType { def value =  9 }
case object ServiceOrEmergencyRoad extends LinkType { def value = 10 }
case object EnclosedTrafficArea    extends LinkType { def value = 11 }
case object TractorRoad            extends LinkType { def value = 12 }
case object MotorwayServiceAccess  extends LinkType { def value = 13 }
case object CableFerry             extends LinkType { def value = 21 }
case object UnknownLinkType        extends LinkType { def value = 99 }

