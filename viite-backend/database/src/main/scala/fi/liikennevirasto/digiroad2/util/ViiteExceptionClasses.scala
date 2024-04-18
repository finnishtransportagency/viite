package fi.liikennevirasto.digiroad2.util

class RoadAddressException(response: String)          extends RuntimeException(response)
class RoadPartReservedException(response: String)     extends RoadAddressException(response)
class MissingTrackException(response: String)         extends RoadAddressException(response)
class MissingRoadwayNumberException(response: String) extends RoadAddressException(response)
