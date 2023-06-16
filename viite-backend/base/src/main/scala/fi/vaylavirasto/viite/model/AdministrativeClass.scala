package fi.vaylavirasto.viite.model

sealed trait AdministrativeClass {
  def value: Int

  /* For APIs which require RoadType values. */
  def asRoadTypeValue: Int = {
    value match {
      case  1 =>  1
      case  2 =>  3
      case  3 =>  5
      case 99 => 99
    }
  }
}

object AdministrativeClass {
  val values = Set(State, Municipality, Private, Unknown)

  def apply(value: Int): AdministrativeClass = {
    values.find(_.value == value).getOrElse(Unknown)
  }

  def apply(value: Long): AdministrativeClass = {
    values.find(_.value == value).getOrElse(Unknown)
  }

  def apply(stringValue: String): AdministrativeClass = {
    values.find(_.toString == stringValue).getOrElse(Unknown)
  }

  case object State extends AdministrativeClass        { def value = 1; }
  case object Municipality extends AdministrativeClass { def value = 2; }
  case object Private extends AdministrativeClass      { def value = 3; }
  case object Unknown extends AdministrativeClass      { def value = 99 }
}

