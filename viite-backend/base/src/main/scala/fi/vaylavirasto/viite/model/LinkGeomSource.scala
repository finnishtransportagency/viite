package fi.vaylavirasto.viite.model

sealed trait LinkGeomSource{
  def value: Int
}

/**
  * LINKIN LÄHDE:
  *  1 = tielinkkien rajapinta,
  *  2 = täydentävien linkkien rajapinta,
  *  4 = jäädytettyjen linkkien rajapinta,
  *  5 = KGV-historialinkkien rajapinta,
  *  6 = KGV-versiorajapinta,
  *  7 = VVH-linkId vastintaulun,
  *  8 = Muutosrajapinta
 *   9 = Lakkautetulle osuudelle luotu duplikaatti
  */

object LinkGeomSource{
  private val values = Set(
    NormalLinkInterface,
    ComplementaryLinkInterface,
    FrozenLinkInterface,
    HistoryLinkInterface,
    RoadLinksVersions,
    LinkCorrespondenceTable,
    Change,
    DuplicateForTerminatedSection,
    Unknown
  )

  def apply(intValue: Int): LinkGeomSource = values.find(_.value == intValue).getOrElse(Unknown)

  case object NormalLinkInterface            extends LinkGeomSource { def value =  1 }
  case object ComplementaryLinkInterface     extends LinkGeomSource { def value =  2 }
  case object FrozenLinkInterface            extends LinkGeomSource { def value =  4 }
  case object HistoryLinkInterface           extends LinkGeomSource { def value =  5 }
  case object RoadLinksVersions              extends LinkGeomSource { def value =  6 }
  case object LinkCorrespondenceTable        extends LinkGeomSource { def value =  7 }
  case object Change                         extends LinkGeomSource { def value =  8 }
  case object DuplicateForTerminatedSection  extends LinkGeomSource { def value =  9 }
  case object Unknown                        extends LinkGeomSource { def value = 99 }
}

