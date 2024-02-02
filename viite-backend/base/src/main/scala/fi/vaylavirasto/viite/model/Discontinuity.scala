package fi.vaylavirasto.viite.model

//JATKUVUUS (1 = Tien loppu, 2 = epäjatkuva (esim. vt9 välillä Akaa-Tampere), 3 = ELY:n raja, 4 = Lievä epäjatkuvuus (esim kiertoliittymä), 5 = jatkuva)
sealed trait Discontinuity {
  def value: Int

  def description: String

  override def toString: String = s"$value - $description"
}

object Discontinuity {
  private val values = Set(EndOfRoad, Discontinuous, ChangingELYCode, MinorDiscontinuity, Continuous)

  def apply(intValue: Int): Discontinuity = {
    values.find(_.value == intValue).getOrElse(Continuous)
  }

  def apply(longValue: Long): Discontinuity = {
    apply(longValue.toInt)
  }

  def apply(s: String): Discontinuity = {
    values.find(_.description.equalsIgnoreCase(s)).getOrElse(Continuous)
  }

  case object EndOfRoad extends Discontinuity {
    def value = 1

    def description = "Tien loppu"
  }

  case object Discontinuous extends Discontinuity {
    def value = 2

    def description = "Epäjatkuva"
  }

  case object ChangingELYCode extends Discontinuity {
    def value = 3

    def description = "ELY:n raja"
  }

  case object MinorDiscontinuity extends Discontinuity {
    def value = 4

    def description = "Lievä epäjatkuvuus"
  }

  case object Continuous extends Discontinuity {
    def value = 5

    def description = "Jatkuva"
  }
}
