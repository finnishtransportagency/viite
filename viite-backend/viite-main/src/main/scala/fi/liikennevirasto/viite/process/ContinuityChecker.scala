package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.viite.dao.{RoadAddress, RoadwayDAO}
import fi.vaylavirasto.viite.model.Track

/**
  * Created by venholat on 15.9.2016.
  */

trait AddressError {
  val roadNumber: Long
  val roadPartNumber: Long
  val startMAddr: Option[Long]
  val endMAddr: Option[Long]
  val linkId: Option[Long]
}

//TODO check the need of that continuity checker
class ContinuityChecker(roadLinkService: RoadLinkService) {

}
