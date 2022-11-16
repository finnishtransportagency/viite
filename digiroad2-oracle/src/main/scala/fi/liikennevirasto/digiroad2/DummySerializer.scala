package fi.liikennevirasto.digiroad2

import java.io.File

import fi.liikennevirasto.digiroad2.client.kgv.ChangeInfo
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.util.KGVSerializer

/**
  * Created by venholat on 2.6.2016.
  */
class DummySerializer extends KGVSerializer{
  override def readCachedGeometry(file: File): Seq[RoadLink] = {
    Seq()
  }

  override def writeCache(file: File, roadLinks: Seq[Object]): Boolean = {
    false
  }

  override def readCachedChanges(file: File): Seq[ChangeInfo] = {
    Seq()
  }

}
