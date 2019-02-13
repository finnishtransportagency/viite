package fi.liikennevirasto

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.SideCode
import fi.liikennevirasto.digiroad2.client.vvh.FeatureClass.AllOthers
import fi.liikennevirasto.digiroad2.client.vvh.{FeatureClass, VVHHistoryRoadLink}
import fi.liikennevirasto.digiroad2.linearasset.RoadLinkLike
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.{BaseRoadAddress, LinkStatus}
import fi.liikennevirasto.viite.dao.Discontinuity.{ChangingELYCode, EndOfRoad}
import fi.liikennevirasto.viite.model.RoadAddressLinkLike
import org.slf4j.Logger

import scala.util.matching.Regex.Match

package object viite {
  /* Tolerance in which we can allow MValues to be equal */
  val MaxAllowedMValueError = 0.001
  /* Smallest mvalue difference we can tolerate values to be "equal to zero". One micrometer.
     See https://en.wikipedia.org/wiki/Floating_point#Accuracy_problems
  */
  val Epsilon = 1E-6

  val MaxDistanceDiffAllowed = 1.0 /* Temporary restriction from PO: Filler limit on modifications
                                      (Linear location adjustments) is limited to 1 meter. If there is a need to fill /
                                      cut more than that then nothing is done to the road address linear location data.

                                      Used also for checking the integrity of the targets of floating road links: no
                                      three roads may have ending points closer to this in the target geometry
                                   */

  val MinAllowedRoadAddressLength = 0.1
  /* No road address can be generated on a segment smaller than this. */

  val MaxMoveDistanceBeforeFloating = 1.0
  /* Maximum amount a road start / end may move until it is turned into a floating road address */

  val MaxDistanceForSearchDiscontinuityOnOppositeTrack = 10.0

  val MinDistanceForGeometryUpdate = 0.5

  val MaxAdjustmentRange = 10L

  val noRoadwayId: Long = 0L

  val noReservedPartId: Long = 0L

  val NewRoadway,

  NewLinearLocation,

  NewRoadwayNumber,

  NewCalibrationPointId,

  NewRoadNameId,

  NewProjectLink: Long = -1000L

  val MaxDistanceForConnectedLinks = 0.1
  /* Used for small jumps on discontinuity or self-crossing tracks */
  val MaxJumpForSection = 50.0
  /* Maximum distance to consider the tracks to go side by side */
  val MaxDistanceBetweenTracks = 50
  /* Maximum distance of regular road link geometry to suravage geometry difference where splitting is allowed */
  val MaxSuravageToleranceToGeometry = 0.5
  val MaxRoadNumberDemandingRoadName = 70000

  //ToDO: remove after Suravage change following messages (4):
  val ErrorNoMatchingProjectLinkForSplit = "Suravage-linkkiä vastaavaa käsittelemätöntä tieosoitelinkkiä ei löytynyt projektista."
  val ErrorSuravageLinkNotFound = "Suravage-linkkiä ei löytynyt."
  val ErrorRoadLinkNotFound = "Tielinkkiä ei löytynyt."
  val ErrorSplitSuravageNotUpdatable = "Valitut linkit sisältävät jaetun Suravage-linkin eikä sitä voi päivittää."

  val ErrorFollowingRoadPartsNotFoundInDB = "Projektiin yritettiin varata tieosia joita ei ole olemassa, tarkista tieosoitteet:"
  val ErrorRoadLinkNotFoundInProject = "Tielinkkiä ei löytynyt projektista. Tekninen virhe, ota yhteys pääkäyttäjään."
  val ErrorRenumberingToOriginalNumber = "Numeroinnissa sekä tie- että  tieosanumero ei voi olla sama kuin alkuperäisellä tieosalla."
  val ErrorRoadNumberDoesNotExist = "Tienumeroa ei ole olemassa, tarkista tiedot."
  val ErrorStartingRoadPartNotFound = "Tiellä ei ole olemassa valittua alkuosaa, tarkista tiedot."
  val ErrorEndingRoadPartNotFound = "Tiellä ei ole olemassa valittua loppuosaa, tarkista tiedot."
  val ErrorGeometryContainsBranches = " Valittu tiegeometria sisältää haarautumia, jotka pitää käsitellä linkkikohtaisesti. Tallennusta ei voi tehdä."
  val ErrorReversingUnchangedLinks = "Tieosalle ei voi tehdä kasvusuunnan kääntöä, koska tieosalla on linkkejä, joita ei ole käsitelty tai jotka on tässä projektissa määritelty säilymään ennallaan."
  val ErrorSavingFailed = "Päivitys ei onnistunut."
  val ErrorMultipleRoadNumbersOrParts = "Useita tieosia valittuna. Numerointi tulee tehdä jokaiselle tieosalle erikseen."
  val MissingEndOfRoadMessage = s"Tieosalle ei ole määritelty jatkuvuuskoodia" + s""" "${EndOfRoad.description}" """ + s"(${EndOfRoad.value}), tieosan viimeiselle linkille."
  val EndOfRoadNotOnLastPartMessage = s"Tieosalle on määritelty jatkuvuuskoodi" + s""" "${EndOfRoad.description}" """ + s"(${EndOfRoad.value}), vaikka tieosan jälkeen on olemassa tieosa."
  val MinorDiscontinuityFoundMessage = "Tieosalla on lievä epäjatkuvuus. Määrittele jatkuvuuskoodi oikein kyseiselle linkille."
  val MajorDiscontinuityFoundMessage = "Tieosalla on epäjatkuvuus. Määrittele jatkuvuuskoodi oikein kyseiselle linkille."
  val InsufficientTrackCoverageMessage = "Tieosalta puuttuu toinen ajorata. Numeroi molemmat ajoradat."
  val DiscontinuousAddressSchemeMessage = "Tieosoitteiden laskenta ei onnistunut. Ota yhteys pääkäyttäjään."

  //VIITE-453 Not implemented yet (2)
  val SharedLinkIdsExistMessage = "Linkillä on voimassa oleva tieosoite tämän projektin alkupäivämäärällä."
  val UnsuccessfulRecalculationMessage = "Etäisyysarvojen laskenta epäonnistui."

  val NoContinuityCodesAtEndMessage = "Tieosan lopusta puuttuu jatkuvuuskoodi."
  val ConnectedDiscontinuousMessage = "Jatkuva tielinkki on merkitty epäjatkuvaksi, korjaa jatkuu-koodi."
  val DifferingDiscontinuityCodesForTracks = " Tieosan lopussa on yhteensopimattomat jatkuu-koodit. Tarkista jatkuu-koodit."
  val ElyCodeChangeNotPresent = s" Tieosan lopussa ei ole jatkuvuuskoodia " + s""" "${ChangingELYCode.description}" """ + s"(${ChangingELYCode.value})."
  val HasNotHandledLinksMessage = "%d kpl käsittelemättömiä linkkejä tiellä %d tieosalla %d."
  val ErrorInValidationOfUnchangedLinksMessage = "Ennallaan toimenpidettä ei voi edeltää muu kuin ennallaan-toimenpide."
  val RampDiscontinuityFoundMessage = "Rampin tieosan sisällä on epäjatkuvuuksia. Tarkista Jatkuu-koodit."
  val RoadNotEndingInElyBorderMessage = "Tien lopussa pitää olla jatkuu-koodi 1. Korjaa jatkuu-koodi."
  val RoadContinuesInAnotherElyMessage = "Jatkuu-koodi %s on virheellinen, koska tie jatkuu toisessa ELY:ssa. "
  val MinorDiscontinuousWhenRoadConnectingRoundabout = "Tieosalla on lievä epäjatkuvuus. Määrittele Jatkuvuuskoodi oikein kyseiselle linkille."
  val WrongDiscontinuityWhenAdjacentToTerminatedRoad = "Tekemäsi tieosoitemuutoksen vuoksi projektin ulkopuoliselle tieosalle täytyy muuttaa jatkuvuuskoodi" + s""" "${EndOfRoad.description}" """ + s"(${EndOfRoad.value}). Muuta jatkuvuuskoodiksi" + s""" "${EndOfRoad.description}" """ + s"(${EndOfRoad.value}) tieosoitteelle %s."
  val DoubleEndOfRoadMessage = "Tekemäsi tieosoitemuutoksen vuoksi projektin ulkopuolisen tieosan jatkuvuuskoodia" + s""" "${EndOfRoad.description}" """ + s"(${EndOfRoad.value}) tulee muuttaa. Tarkasta ja muuta tieosoitteen %s jatkuvuuskoodi."
  val EndOfRoadMiddleOfPartMessage = s"Tieosan keskellä olevalla linkillä on jatkuvuuskoodi" + s""" "${EndOfRoad.description}" """ + s"(${EndOfRoad.value})."
  val RoadNameWasNotSavedInProject = "Datavirhe tien nimetyksessä, ota yhteys pääkäyttäjään."
  val RoadNotAvailableMessage = s"Varattujen tieosien haku tietokannasta epäonnistui. Tie %d osa %d ei ole varattavissa, koska se ei ole voimassa projektin alkupvm:llä %s tai se on varattu toiseen projektiin."
  val RoadReservedOtherProjectMessage = s"Tie %d osa %d on jo varattuna projektissa %s, tarkista tiedot."
  val ProjectNotFoundMessage = "Projektia ei löytynyt, ota yhteys pääkäyttäjään."
  val FailedToSendToTRMessage = s"Lähetys tierekisteriin epäonnistui, ota yhteys pääkäyttäjään."
  val TrConnectionError = s"Muutosilmoitus ei tavoittanut Tierekisteriä. Muutosilmoitus lähetetään automaattisesti uudelleen aina 5 minuutin välein.\r\n" +
    s"Virhetilanteen jatkuessa ota yhteytta ylläpitoon."
  val GenericViiteErrorMessage = s"Muutosilmoituksen lähetys epäonnistui Viiteen sisäisen virheen vuoksi. Ota yhteyttä ylläpitoon. "
  val ProjectNotWritable = s"Projekti ei ole enää muokattavissa."

  //ELY-code error messages
  val MultipleElysInPartMessage = s"Samalla tieosalla eri elynumeroita. Tieosan tulee vaihtua ELY-rajalla. Korjaa tieosa- tai elynumeroa."
  val IncorrectLinkStatusOnElyCodeChangeMessage =  s"ELY-koodin muutos ei onnistu, ota yhteyttä pääkäyttäjään."
  val ElyCodeChangeButNoRoadPartChangeMessage = s"ELY-numeromuutos havaittu mutta tieosoitemuutos puuttuu. Tieosanumeron tulee vaihtua ELY-rajalla."
  val ElyCodeChangeButNoElyChangeMessage = s"ELY-numeromuutos havaittu mutta  ${ChangingELYCode.description}(${ChangingELYCode.value}) jatkuvuuskoodi on väärä. ELY:n rajalla jatkuvuuskoodin tulee olla 3."
  val ElyCodeDiscontinuityChangeButNoElyChangeMessage = s"Tieosan %d lopussa jatkuu-koodiksi määritelty ${ChangingELYCode.description} (${ChangingELYCode.value}), tarkista tieosien %d ja %s ELY-koodit tai korjaa jatkuu-koodia."
  val ElyCodeChangeButNotOnEndMessage = s"Tieosan keskellä on jatkuu-koodiksi määritelty ${ChangingELYCode.value}, korjaa jatkuu-koodi."

  //RoadNetworkChecker error messages
  val ErrorOverlappingRoadAddress = "Road address overlaps another one."
  val ErrorInconsistentTopology = "Topology have inconsistent data."
  val ErrorInconsistentLrmHistory = "Lrm with inconsistent history."
  val ErrorInconsistent2TrackCalibrationPoints = "Missing relative calibration point in opposite track."
  val ErrorInconsistentContinuityCalibrationPoints = "Missing relative connecting starting point."
  val ErrorMissingEdgeCalibrationPoints = "Missing edge calibration points."
  val ErrorInconsistentAddressValues = "Error in continuity by address m values between connected links."
  val InconsistentAddressValues = "Wrong address values between links."

  val RampsMinBound = 20001
  val RampsMaxBound = 39999

  val MaxLengthChange = 20.0

  val DefaultScreenWidth = 1920
  val DefaultScreenHeight = 1080
  val Resolutions = Array(2048, 1024, 512, 256, 128, 64, 32, 16, 8, 4, 2, 1, 0.5, 0.25, 0.125, 0.0625)
  val DefaultLongitude = 6900000.0
  val DefaultLatitude = 390000.0
  val DefaultZoomLevel = 2
  val operationsLeavingHistory = List(LinkStatus.Transfer, LinkStatus.Numbering, LinkStatus.Terminated)

  val defaultProjectEly = -1L

  def parseStringGeometry(geomString: String): Seq[Point] = {
    if (geomString.nonEmpty)
      toGeometry(geomString)
    else
      Seq()
  }

  def toGeometry(geometryString: String): Seq[Point] = {
    def toBD(s: String): Double = {
      BigDecimal(s).setScale(3, BigDecimal.RoundingMode.HALF_UP).toDouble
    }
    val pointRegex = raw"\[[^\]]*]".r
    val regex = raw"\[(\-?\d+\.?\d*),(\-?\d+\.?\d*),?(\-?\d*\.?\d*)?\]".r
    pointRegex.findAllIn(geometryString).map {
      case regex(x, y, z) if z != "" => Point(toBD(x), toBD(y), toBD(z))
      case regex(x, y, _) => Point(toBD(x), toBD(y))
    }.toSeq
  }

  def switchSideCode(sideCode: SideCode): SideCode = {
    // Switch between against and towards 2 -> 3, 3 -> 2
    SideCode.apply(5-sideCode.value)
  }

  private def isRamp(roadNumber: Long, trackCode: Long): Boolean = {
    roadNumber >= RampsMinBound && roadNumber <= RampsMaxBound && trackCode == 0
  }

  def isRamp(r: RoadAddressLinkLike): Boolean = {
    isRamp(r.roadNumber, r.trackCode)
  }

  def isRamp(r: BaseRoadAddress): Boolean = {
    isRamp(r.roadNumber, r.track.value)
  }

  object CombineMaps {
    type Mapped = Map[String, String]

    def combine(x: Mapped, y: Mapped): Mapped = {
      val x0 = x.withDefaultValue("")
      val y0 = y.withDefaultValue("")
      val keys = x.keys.toSet.union(y.keys.toSet)
      keys.map { k => k -> (x0(k) + y0(k)) }.toMap
    }

  }

  implicit class CaseClassToString(c: AnyRef) {
    def toStringWithFields: String = {
      val fields = (Map[String, Any]() /: c.getClass.getDeclaredFields) { (a, f) =>
        f.setAccessible(true)
        a + (f.getName -> f.get(c))
      }
      s"${c.getClass.getName}(${fields.mkString(", ")})"
    }
  }

}
