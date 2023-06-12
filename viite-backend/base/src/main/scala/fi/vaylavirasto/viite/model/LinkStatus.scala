package fi.vaylavirasto.viite.model

//TODO LinkStatus should be renamed to ProjectLinkStatus
sealed trait LinkStatus {
  def value: Int
  def description: String
}

//TODO LinkStatus should be renamed to ProjectLinkStatus
object LinkStatus {
  val values = Set(NotHandled, Terminated, New, Transfer, UnChanged, Numbering, Unknown)
  case object NotHandled extends LinkStatus {def value =  0; def description = "Käsittelemättä"}
  case object UnChanged  extends LinkStatus {def value =  1; def description = "Ennallaan"}
  case object New        extends LinkStatus {def value =  2; def description = "Uusi"}
  case object Transfer   extends LinkStatus {def value =  3; def description = "Siirto"}
  case object Numbering  extends LinkStatus {def value =  4; def description = "Numerointi"}
  case object Terminated extends LinkStatus {def value =  5; def description = "Lakkautettu"}
  case object Unknown    extends LinkStatus {def value = 99; def description = "Tuntematon"}

  def apply(intValue: Int): LinkStatus = {
    values.find(_.value == intValue).getOrElse(Unknown)
  }
}
