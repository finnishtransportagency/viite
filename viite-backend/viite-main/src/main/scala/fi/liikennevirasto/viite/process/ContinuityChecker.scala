package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.vaylavirasto.viite.model.RoadPart

/**
  * Created by venholat on 15.9.2016.
  */

trait AddressError {
  val roadPart: RoadPart
  val startMAddr: Option[Long]
  val endMAddr: Option[Long]
  val linkId: Option[Long]
}

//TODO check the need of that continuity checker
class ContinuityChecker(roadLinkService: RoadLinkService) {

}
