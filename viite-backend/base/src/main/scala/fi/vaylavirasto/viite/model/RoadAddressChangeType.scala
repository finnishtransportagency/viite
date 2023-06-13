package fi.vaylavirasto.viite.model

sealed trait RoadAddressChangeType {
  def value: Int

  def description: String
}

/**
  * Types for the road address network change types available in Viite ("toimenpide" in finnish).
  * RoadAddressChangeType describes, what kind of an operation is made for a part of a road (typically, of length of a link, or a roadway)
  * Has also NotHandled, and Unknown "change types". NotHandled for links just picked for changes but not yet changed,
  * and Unknown whose usage should be checked; seems almost useless, and pointless (<- TODO!).
  */
object RoadAddressChangeType {
  val values = Set(NotHandled, Unchanged, New, Transfer, Renumeration, Termination)

  def apply(intValue: Int): RoadAddressChangeType = {
    values.find(_.value == intValue).getOrElse(Unknown)
  }

  /*  - NotHandled: Tells that the road address is to be changed, but the change is yet to be defined.
      - Unchanged: A meta operation or no-operation, section address stays intact but it needs to be included either for
                   other road part completeness' sake, or for meta information changes (administrative class, ELY, or like).
      - New: An operation, where a road that did not have road address before gets a road address.
      - Transfer: An adjustment of a road address, such as extending a road 100 meters from the start (all the
                  addresses on the first part are transferred with +100 to each start and end address M values), or
                  changing the address of the road to another road number, or road part number, or change of a track.
      - Renumeration: A change in road addressing for a whole road part, with no physical or length changes.
                      A road part gets another road and/or road part number.
      - Termination: For ending a road address at this location. (The same address may be assigned to a new physical
                     location even at the same. So, termination does not restrict the usage of the address that gets
                     terminated. It just ends the address existence at its current location.)
      - Unknown: Should not usually be used - But a default value when there is yet no information available.
   */

  case object NotHandled   extends RoadAddressChangeType { def value =  0; def description = "Käsittelemätön"}
  case object Unchanged    extends RoadAddressChangeType { def value =  1; def description = "Ennallaan"     }
  case object New          extends RoadAddressChangeType { def value =  2; def description = "Uusi"          }
  case object Transfer     extends RoadAddressChangeType { def value =  3; def description = "Siirto"        }
  case object Renumeration extends RoadAddressChangeType { def value =  4; def description = "Numerointi"    }
  case object Termination  extends RoadAddressChangeType { def value =  5; def description = "Lakkautus"     }
  case object Unknown      extends RoadAddressChangeType { def value = 99; def description = "Tuntematon"    }

}

