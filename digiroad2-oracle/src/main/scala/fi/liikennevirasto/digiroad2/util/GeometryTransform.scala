package fi.liikennevirasto.digiroad2.util

/**
  * A road consists of 1-2 tracks (fi: "ajorata"). 2 tracks are separated by a fence or grass for example.
  * Left and Right are relative to the advancing direction (direction of growing m values)
  */
sealed trait Track {
  def value: Int

  override def toString: String = value.toString
}
object Track {
  val values = Set(Combined, RightSide, LeftSide, Unknown)

  def apply(intValue: Int): Track = {
    values.find(_.value == intValue).getOrElse(Unknown)
  }

  def applyOption(track: Option[Int]): Option[Track] = {
    if (track.nonEmpty) {
      Some(apply(track.get))
    } else {
      None
    }
  }

  def applyAll(values: Iterable[Int]): Set[Track] = {
    values.map(value => Track.apply(value)).toSet
  }

  /**
    * Switch left to right and vice versa
    * @param track Track value to switch
    * @return
    */
  def switch(track: Track): Track = {
    track match {
      case RightSide => LeftSide
      case LeftSide => RightSide
      case _ => track
    }
  }

  def isTrackContinuous(prev: Track, next: Track): Boolean = {
    prev == next || prev == Track.Combined || next == Track.Combined
  }

  case object Combined extends Track { def value = 0 }
  case object RightSide extends Track { def value = 1 }
  case object LeftSide extends Track { def value = 2 }
  case object Unknown extends Track { def value = 99 }
}

class RoadAddressException(response: String) extends RuntimeException(response)
class RoadPartReservedException(response: String) extends RoadAddressException(response)
