package fi.liikennevirasto.digiroad2.client.kmtk

// TODO Change this to work with KMTK

case class ChangeInfo(oldId: Option[Long], newId: Option[Long], mmlId: Long, changeType: ChangeType,
                      oldStartMeasure: Option[Double], oldEndMeasure: Option[Double], newStartMeasure: Option[Double],
                      newEndMeasure: Option[Double], timeStamp: Long = 0L) {
  def isOldId(id: Long): Boolean = {
    oldId.nonEmpty && oldId.get == id
  }

  def affects(id: Long, assetTimeStamp: Long): Boolean = {
    isOldId(id) && assetTimeStamp < timeStamp
  }
}
