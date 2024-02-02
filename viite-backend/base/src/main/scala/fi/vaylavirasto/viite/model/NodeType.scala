package fi.vaylavirasto.viite.model

sealed trait NodeType {
  def value: Int

  def displayValue: String
}

object NodeType {
  val values: Set[NodeType] = Set(
    NormalIntersection, Roundabout1, Roundabout, YIntersection, Interchange,
    InterchangeJunction, RoadBoundary, ELYBorder, SupportingPoint, MultitrackIntersection,
    DropIntersection, AccessRoad, EndOfRoad, Bridge, MaintenanceOpening, PrivateRoad, StaggeredIntersection, Ferry,
    UnknownNodeType)

  def apply(intValue: Int): NodeType = {
    values.find(_.value == intValue).getOrElse(UnknownNodeType)
  }

  case object NormalIntersection     extends NodeType {    def value =  1;    def displayValue = "Normaali tasoliittymä"    }
  case object Roundabout1            extends NodeType {    def value =  2;    def displayValue = "Kiertoliittymä1"          }
  case object Roundabout             extends NodeType {    def value =  3;    def displayValue = "Kiertoliittymä"           }
  case object YIntersection          extends NodeType {    def value =  4;    def displayValue = "Y-liittymä"               }
  case object Interchange            extends NodeType {    def value =  5;    def displayValue = "Eritasoliittymä"          }
  case object InterchangeJunction    extends NodeType {    def value =  6;    def displayValue = "Eritasoristeys"           }
  case object RoadBoundary           extends NodeType {    def value =  7;    def displayValue = "Hallinnollinen raja"      }
  case object ELYBorder              extends NodeType {    def value =  8;    def displayValue = "ELY-raja"                 }
  case object SupportingPoint        extends NodeType {    def value =  9;    def displayValue = "Apupiste"                 }
  case object MultitrackIntersection extends NodeType {    def value = 10;    def displayValue = "Moniajoratainen liittymä" }
  case object DropIntersection       extends NodeType {    def value = 11;    def displayValue = "Pisaraliittymä"           }
  case object AccessRoad             extends NodeType {    def value = 12;    def displayValue = "Liityntätie"              }
  case object EndOfRoad              extends NodeType {    def value = 13;    def displayValue = "Tien alku/loppu"          }
  case object Bridge                 extends NodeType {    def value = 14;    def displayValue = "Silta"                    }
  case object MaintenanceOpening     extends NodeType {    def value = 15;    def displayValue = "Huoltoaukko"              }
  case object PrivateRoad            extends NodeType {    def value = 16;    def displayValue = "Yksityistie- tai katuliittymä" }
  case object StaggeredIntersection  extends NodeType {    def value = 17;    def displayValue = "Porrastettu liittymä"     }
  case object Ferry                  extends NodeType {    def value = 18;    def displayValue = "Lautta"                   }

  case object UnknownNodeType        extends NodeType {    def value = 99;    def displayValue = "Ei määritelty"            }

}

