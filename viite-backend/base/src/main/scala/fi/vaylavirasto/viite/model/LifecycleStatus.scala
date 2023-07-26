package fi.vaylavirasto.viite.model

sealed trait LifecycleStatus {
  def value: Int
}

object LifecycleStatus{
  private val values = Set[LifecycleStatus](
    Planned,
    UnderConstruction,
    InUse,
    TemporarilyNotInUse,
    ExpiringSoon,
    UnknownLifecycleStatus
  )

  def apply(intValue: Int): LifecycleStatus = {
    values.find(_.value == intValue).getOrElse(InUse)
  }

  case object Planned                extends LifecycleStatus { def value =  1 }
  case object UnderConstruction      extends LifecycleStatus { def value =  2 }
  case object InUse                  extends LifecycleStatus { def value =  3 }
  case object TemporarilyNotInUse    extends LifecycleStatus { def value =  4 }
  case object ExpiringSoon           extends LifecycleStatus { def value =  5 }
  case object UnknownLifecycleStatus extends LifecycleStatus { def value = 99 }
}

