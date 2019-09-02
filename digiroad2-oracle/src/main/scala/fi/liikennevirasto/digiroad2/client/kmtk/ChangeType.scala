package fi.liikennevirasto.digiroad2.client.kmtk

import fi.liikennevirasto.digiroad2.client.kmtk.ChangeType._

// TODO Change this to work with KMTK

/**
  * Numerical values for change types from KMTK ChangeInfo API
  */
sealed trait ChangeType {
  def value: Int

  def isShortenedChangeType: Boolean = {
    ChangeType.apply(value) match {
      case ShortenedCommonPart => true
      case ShortenedRemovedPart => true
      case _ => false
    }
  }

  def isLengthenedChangeType: Boolean = {
    ChangeType.apply(value) match {
      case LengthenedCommonPart => true
      case LengthenedNewPart => true
      case _ => false
    }
  }

  def isDividedChangeType: Boolean = {
    ChangeType.apply(value) match {
      case DividedNewPart => true
      case DividedModifiedPart => true
      case _ => false
    }
  }

  def isCombinedChangeType: Boolean = {
    ChangeType.apply(value) match {
      case CombinedModifiedPart => true
      case CombinedRemovedPart => true
      case _ => false
    }
  }
}

object ChangeType {
  val values = Set(Unknown, CombinedModifiedPart, CombinedRemovedPart, LengthenedCommonPart, LengthenedNewPart, DividedModifiedPart, DividedNewPart, ShortenedCommonPart, ShortenedRemovedPart, Removed, New, ReplacedCommonPart, ReplacedNewPart, ReplacedRemovedPart)

  def apply(intValue: Int): ChangeType = {
    values.find(_.value == intValue).getOrElse(Unknown)
  }

  case object Unknown extends ChangeType { def value = 0 }

  case object CombinedModifiedPart extends ChangeType { def value = 1 }
  case object CombinedRemovedPart extends ChangeType { def value = 2 }

  case object LengthenedCommonPart extends ChangeType { def value = 3 }
  case object LengthenedNewPart extends ChangeType { def value = 4 }

  case object DividedModifiedPart extends ChangeType { def value = 5 }
  case object DividedNewPart extends ChangeType { def value = 6 }

  case object ShortenedCommonPart extends ChangeType { def value = 7 }
  case object ShortenedRemovedPart extends ChangeType { def value = 8 }

  case object Removed extends ChangeType { def value = 11 }
  case object New extends ChangeType { def value = 12 }
  case object ReplacedCommonPart extends ChangeType { def value = 13 }
  case object ReplacedNewPart extends ChangeType { def value = 14 }
  case object ReplacedRemovedPart extends ChangeType { def value = 16 }

  /**
    * Return true if this is a replacement where segment or part of it replaces another, older one
    * All changes should be of form (old_id, new_id, old_start, old_end, new_start, new_end) with non-null values
    *
    * @param changeInfo changeInfo object to check
    * @return true, if this is a replacement
    */
  def isReplacementChange(changeInfo: ChangeInfo): Boolean = { // Where asset geo location should be replaced with another
    changeInfo.changeType match {
      case CombinedModifiedPart => true
      case CombinedRemovedPart => true
      case LengthenedCommonPart => true
      case DividedModifiedPart => true
      case DividedNewPart => true
      case ShortenedCommonPart => true
      case ReplacedCommonPart => true
      case Unknown => false
      case LengthenedNewPart => false
      case ShortenedRemovedPart => false
      case Removed => false
      case New => false
      case ReplacedNewPart => false
      case ReplacedRemovedPart => false
    }
  }

  /**
    * Return true if this is an extension where segment or part of it has no previous entry
    * All changes should be of form (new_id, new_start, new_end) with non-null values and old_* fields must be null
    *
    * @param changeInfo changeInfo object to check
    * @return true, if this is an extension
    */
  def isExtensionChange(changeInfo: ChangeInfo): Boolean = { // Where asset geo location is a new extension (non-existing)
    changeInfo.changeType match {
      case LengthenedNewPart => true
      case ReplacedNewPart => true
      case _ => false
    }
  }

  /**
    * Return true if this is a removed segment or a piece of it. Only old id and m-values should be populated.
    *
    * @param changeInfo changeInfo object to check
    * @return true, if this is a removed segment
    */
  def isRemovalChange(changeInfo: ChangeInfo): Boolean = { // Where asset should be removed completely or partially
    changeInfo.changeType match {
      case Removed => true
      case ReplacedRemovedPart => true
      case ShortenedRemovedPart => true
      case _ => false
    }
  }

  /**
    * Return true if this is a new segment. Only new id and m-values should be populated.
    *
    * @param changeInfo changeInfo object to check
    * @return true, if this is a new segment
    */
  def isCreationChange(changeInfo: ChangeInfo): Boolean = { // Where asset geo location should be replaced with another
    changeInfo.changeType match {
      case New => true
      case _ => false
    }
  }

  def isUnknownChange(changeInfo: ChangeInfo): Boolean = {
    ChangeType.Unknown == changeInfo.changeType
  }
}