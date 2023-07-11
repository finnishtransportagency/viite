package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.client.kgv.ChangeInfo
import fi.liikennevirasto.digiroad2.client.kgv.ChangeType._
import org.slf4j.LoggerFactory


object RoadAddressChangeInfoMapper extends RoadAddressMapper {
  private val logger = LoggerFactory.getLogger(getClass)

  private def isLengthChange(ci: ChangeInfo) = {
    Set(LengthenedCommonPart.value, LengthenedNewPart.value, ShortenedCommonPart.value, ShortenedRemovedPart.value).contains(ci.changeType.value)
  }

  private def max(doubles: Double*) = {
    doubles.max
  }

  private def min(doubles: Double*) = {
    doubles.min
  }

  private def fuseLengthChanges(sources: Seq[ChangeInfo]) = {
    val (lengthened, rest) = sources.partition(ci => ci.changeType.value == LengthenedCommonPart.value ||
      ci.changeType.value == LengthenedNewPart.value)
    val (shortened, others) = rest.partition(ci => ci.changeType.value == ShortenedRemovedPart.value ||
      ci.changeType.value == ShortenedCommonPart.value)
    others ++
      lengthened.groupBy(ci => (ci.newId, ci.timeStamp)).mapValues { s =>
        val common = s.find(_.changeType.value == LengthenedCommonPart.value)
        val added = s.find(st => st.changeType.value == LengthenedNewPart.value)
        (common, added) match {
          case (Some(c), Some(a)) =>
            val (expStart, expEnd) = if (c.newStartMeasure.get > c.newEndMeasure.get)
              (max(c.newStartMeasure.get, a.newStartMeasure.get, a.newEndMeasure.get), min(c.newEndMeasure.get, a.newStartMeasure.get, a.newEndMeasure.get))
            else
              (min(c.newStartMeasure.get, a.newStartMeasure.get, a.newEndMeasure.get), max(c.newEndMeasure.get, a.newEndMeasure.get, a.newStartMeasure.get))
            Some(c.copy(newStartMeasure = Some(expStart), newEndMeasure = Some(expEnd)))
          case _ => None
        }
      }.values.flatten.toSeq ++
      shortened.groupBy(ci => (ci.oldId, ci.timeStamp)).mapValues { s =>
        val common = s.find(_.changeType.value == ShortenedCommonPart.value)
        val toRemove = s.filter(_.changeType.value == ShortenedRemovedPart.value)
        val fusedRemove = if (toRemove.lengthCompare(0) > 0) {
          Some(toRemove.head.copy(oldStartMeasure = toRemove.minBy(_.oldStartMeasure).oldStartMeasure, oldEndMeasure = toRemove.maxBy(_.oldEndMeasure).oldEndMeasure))
        } else None
        (common, fusedRemove) match {
          case (Some(c), Some(r)) =>
            val (expStart, expEnd) = if (c.oldStartMeasure.get > c.oldEndMeasure.get)
              (max(c.oldStartMeasure.get, r.oldStartMeasure.get, r.oldEndMeasure.get), min(c.oldEndMeasure.get, c.newStartMeasure.get, r.oldEndMeasure.get))
            else
              (min(c.oldStartMeasure.get, r.oldStartMeasure.get, r.oldEndMeasure.get), max(c.oldEndMeasure.get, r.oldEndMeasure.get, r.oldStartMeasure.get))
            Some(c.copy(oldStartMeasure = Some(expStart), oldEndMeasure = Some(expEnd)))
          case _ => None
        }
      }.values.flatten.toSeq
  }

}
