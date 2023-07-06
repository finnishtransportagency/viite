package fi.vaylavirasto.viite.model

sealed trait BeforeAfter {
  def value: Long
  def acronym: String
}

object BeforeAfter {
  val values: Set[BeforeAfter] = Set(Before, After, UnknownBeforeAfter)

  def apply(intValue: Long): BeforeAfter = {
    values.find(_.value == intValue).getOrElse(UnknownBeforeAfter)
  }

  def switch(beforeAfter: BeforeAfter): BeforeAfter = {
    beforeAfter match {
      case After => Before
      case Before => After
      case _ => beforeAfter
    }
  }

  case object Before extends BeforeAfter {
    def value = 1
    def acronym = "E"
  }

  case object After extends BeforeAfter {
    def value = 2
    def acronym = "J"
  }

  case object UnknownBeforeAfter extends BeforeAfter {
    def value = 9
    def acronym = ""
  }

}
