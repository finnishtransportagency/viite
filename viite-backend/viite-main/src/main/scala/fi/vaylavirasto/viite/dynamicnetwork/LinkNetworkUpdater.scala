package fi.vaylavirasto.viite.dynamicnetwork

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonParseException
import fi.liikennevirasto.digiroad2.client.kgv.KgvRoadLink
import fi.liikennevirasto.digiroad2.util.LogUtils
import fi.liikennevirasto.viite.NewIdValue
import fi.liikennevirasto.viite.dao.{CalibrationPointDAO, CalibrationPointReference, LinearLocation, LinearLocationDAO}
import fi.vaylavirasto.viite.dao.LinkDAO
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point}
import fi.vaylavirasto.viite.model.CalibrationPointLocation.{EndOfLink, StartOfLink}
import fi.vaylavirasto.viite.model.{CalibrationPoint, LinkGeomSource, SideCode}
import fi.vaylavirasto.viite.postgis.PostGISDatabase
import fi.vaylavirasto.viite.util.ViiteException
import org.joda.time.DateTime
import org.json4s.jackson.JsonMethods
import org.json4s.{DefaultFormats, MappingException}
import org.slf4j.{Logger, LoggerFactory}


// Data classes for LinkNetworkUpdater

case class PointWkt(@JsonProperty("x") x: Double,
                    @JsonProperty("y") y: Double,
                    @JsonProperty("z") z: Double = 0.0) {
  def toPoint: Point = {
    Point(x, y, z)
  }
}

/**
 * Information about a <i>complete</i> link participating in a LinkNetworkChange.
 * Use for both the current ("old"), and the replacing ("new") links.
 *
 * @param linkId Unique identifier of the link.
 * @param linkLength (2D) Geometry length of the whole link, from start (0) to the other end of the geometry. Assumed 3 decimals, and in [m].
 * @param geometry  The whole geometry of the link. Taken as Point(x,y,z), but only (x,y) considered in the update calculations, where necessary.
 */
case class LinkInfo(linkId: String,
                    linkLength: Double,
                    geometry: Seq[Point] // TODO Point ok for the type?
                    )
/**
 * Meta data corresponding to a replaceInfo
 *
 * @param roadwayNumber    Roadway number  this (part of the) old link belongs to
 * @param linearLocationId Linear location this (part of the) old link belongs to
 * @param orderNumber      Number telling the ordering of the linear locations on the roadway
 * @param roadNumber       Road number     where this linear location belongs to
 * @param roadPartNumber   Road part of the road this linear location belongs to
 */
case class ViiteMetaData(linearLocationId: Long,
                         mValueStart:      Double,
                         mValueEnd:        Double,
                         roadwayNumber:    Long,
                         orderNumber:      Int,
                         roadNumber:       Long,
                         roadPartNumber:   Long
                        )


/**
 * Replace info, telling which part of the current ("old") link gets replaced with which part of the replacing ("new") link.
 *
 * @param oldLinkId     Id of the link to be replaced with new link(s). Link data must be found in the oldlink [[LinkInfo]] data.
 * @param oldFromMValue Defines the start of the section on the old link's geometry, where (a part of) the old link is to be replaced with (a part of) the new link.
 *                      Usually 0. Bigger value legit only when splitting the old link,
 *                      and this replace is other than the first part of it. Smaller never.
 * @param oldToMValue   Defines the end of the section on the old link's geometry, where (a part of) the old link is to be replaced with (a part of) the new link.
 *                      Usually the same as the geometry length of the old link (rounded to 3 decimals). Smaller value legit only when
 *                      splitting the old link, and this replace is other than the last part of it. Bigger never.
 * @param newLinkId     Id of a link to be replacing (a part of the) the old link. Link data must be found in the newLinks [[LinkInfo]] data.
 * @param newFromMValue Defines the start of the section on the new link's geometry, where (a part of) the new link replaces (a part of) the old link.
 *                      Usually 0. Bigger value legit only when combining old links,
 *                      and this replace is other than the first part of the combine. Smaller never.
 * @param newToMValue   Defines the end of the section on the new link's geometry, where (a part of) the new link replaces (a part of) the old link.
 *                      Usually the same as the geometry length of the new link (rounded to 3 decimals). Smaller value legit only when
 *                      combining old links, and this replace is other than the last part of the combine. Bigger never.
 * @param digitizationChange Tells, whether the drawing direction of the new link is opposite of the old link, or just
 *                           the same as before. True for opposite direction, false for staying the same.
 * @param oldLinkViiteData Data describing the old link related data in the Viite structures.
 */
case class ReplaceInfo( oldLinkId: String, oldFromMValue: Double, oldToMValue: Double,
                        newLinkId: String, newFromMValue: Double, newToMValue: Double,
                        digitizationChange: Boolean,
                        oldLinkViiteData: Seq[ViiteMetaData]
                       )
/**
 * A generic network link change type, for reading the whole JSON ("samuutussetti") in.
 *
 * @note excess JSONArray braces required due to JsonMethods.parse functionality. Correct, when the library allows.
 * (See [[LinkNetworkUpdater.persistLinkNetworkChanges]].)
 *
 * Example:
 * <pre>
 * [
 * {
 *     "changeType": "replace",
 *     "old": {
 *         "linkId": "oldLink:1",
 *         "linkLength": 43.498,
 *         "geometry":  "LINESTRING ZM(x y zstart, [... ,] x y zend)" // At least start, and end points of the geometry
 *         },
 *     "new": [
 *         {
 *             "linkId": "newLink:1",
 *             "linkLength": 49.631,
 *             "geometry":  "LINESTRING ZM(x y zstart, ... , x y zend)" // The whole geometry required for possibility to split to linear locations
 *         },
 *         { ... }
 *     ],
 *     "replaceInfo": [
 *         {
 *             "oldLinkId": "oldLink:1",
 *             "oldFromMValue": 0.0,
 *             "oldToMValue": 43.498,
 *
 *             "newLinkId": "newLink:1",
 *             "newFromMValue": 0.0,
 *             "newToMValue": 49.631,
 *
 *             "digitizationChange": false
 *         }
 *     ]
 * },
 * { ... }
 * ]
 * </pre>
 *
 * @param changeType Type of the change. A "replace", or a "split".
 * @param oldLink  Data describing the old link in the link network, before the change.
 * @param newLinks Data describing the new links that replace the oldLink in the updated link network.
 * @param replaceInfos Data describing the relation (correspondence) between the oldLink, and the newLinks.
 */
case class LinkNetworkChange(changeType:  String,
                             oldLink:     LinkInfo,
                             newLinks:    Seq[LinkInfo],
                             replaceInfos: Seq[ReplaceInfo]
                            )
/**
 * A NetworkLink change of type "replace". Use only for validated, proper changes of type "replace".
 * A replace is a change, where a link gets fully replaced by (whole, or a part of) a single other link.
 * The link may have been splitted to multiple linear locations (gets multiple replaceInfos), but
 * every one of them gets a replacement from the same new link.
 * Use function convertToAValidReplaceChange for validation, and conversion. */
private case class LinkNetworkReplaceChange(oldLink: LinkInfo,
                                            newLink: LinkInfo,
                                            replaceInfos: Seq[ReplaceInfo]
                                           )
/** A NetworkLink change of type "split". Use only for validated, proper changes of type "split".
 * Use function convertToAValidSplitChange for validation, and conversion. */
private case class LinkNetworkSplitChange(oldLink:     LinkInfo,
                                          newLinks:    Seq[LinkInfo],
                                          replaceInfos: Seq[ReplaceInfo]
                                         )

/**
 * Metadata common to every change within the single call of [[LinkNetworkUpdater.persistLinkNetworkChanges]]
 *
 * @param changeSetName  Name of the change set, whose data is to be saved. Used as
 *                       data creator's "name", and for logging / throw messages.
 * @param linkDataRetrievalDate Time (but date accuracy preferred) of the link data retrieval.
 *                              Used as timestamps for the db lines created, that take date as a parameter
 * @param linkGeomSource Source from where the link data / geometries of the set have been retrieved
 */
case class ChangeSetMetaData(changeSetName: String,
                             linkDataRetrievalDate: DateTime,
                             linkGeomSource: LinkGeomSource
                            )

/**
 * LinkNetworkUpdater translates the dynamic link network change sets to proper Viite data changes, and persists them into the database.
 * @see [[persistLinkNetworkChanges]], and [[LinkNetworkChange]]
 *
 * Viite accepts two types of changes; replace, and split. //TODO How about combine? Is it different from these two?
 * All of the incoming changes are supposed to keep network address topology intact.
 * That is,
 * <li>no road address shall be changed due to any link network change, and</li>
 * <li>the nodes, and junctions must also have their relative positions after the changes.</li>
 * The visible link on the map may change its position slightly, but the junctions must stay topologically the same.
 *
 * What does the "no road address shall be changed due to any link network change" mean:
 * Every old, and new link mentioned in the given change set (either as JSON, or as Seq[LinkNetworkChange])
 * must be handled as a whole within the change set.
 * Every m value from 0 to the length of the link must be given the corresponding old, or new link within a given change set.
 * LinkNetworkUpdater does not check this, but the link network gets corrupted, if this is not the case.
 */
class LinkNetworkUpdater {

  val logger: Logger = LoggerFactory.getLogger(getClass)
  private implicit val formats: DefaultFormats.type = DefaultFormats // json4s requires this for extract, and extractOpt

  // define here, to be able to override from spec file with a no-operation
  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)

  private val linearLocationDAO = new LinearLocationDAO // LinearLocationDAO is a class, for testing possibility (must be able to override db functionality)
  private val kgvClient         = new KgvRoadLink

  /**
   * Takes in a change set JSON, and extracts a list of [[LinkNetworkChange]]s out of it.
   *
   * @param changeSetJSON JSON containing dynamic link network changes, of type Seq[ [[LinkNetworkChange]] ]
   * @param changeSetName Name of the set the given JSON has been received from. Used for logging.
   * @return List of [[LinkNetworkChange]]s.
   * @throws ViiteException if the given JSON cannot be deserialized to a list of [[LinkNetworkChange]]s
   */
  def parseJSONForLinkNetworkChanges(changeSetJSON: String, changeSetName: String): Seq[LinkNetworkChange] = {

    def getSamingSet(changeSetJSON: String): Seq[LinkNetworkChange] = {
      JsonMethods.parse(changeSetJSON)
        .extract[Seq[LinkNetworkChange]] // We assume to get at least one object to process. If none possible, use .extractOpt
      //.getOrElse(Seq.empty[LinkNetworkChange])
    }

    def reThrow(myMessage: String, t: Throwable) = {
      val excStr = s"An exception when parsing dynamic link network change set $changeSetName"
      throw ViiteException(s"$excStr:\r  $myMessage\r    ${t.getMessage}\r\r")
    }

    LogUtils.time(logger, s"Persist LinkNetworkChanges to Viite data structures $changeSetName") {

      try {
        changeSetJSON match {
          case ""     => Seq[LinkNetworkChange]() // Nothing to process
          case "[]"   => Seq[LinkNetworkChange]() // Nothing to process
          case "{}"   => Seq[LinkNetworkChange]() // Nothing to process
          case "[{}]" => Seq[LinkNetworkChange]() // Nothing to process
          case _ => getSamingSet(changeSetJSON) // may throw ViiteException
        }
      }
      catch {
        case e: JsonParseException => reThrow("Incorrectly formatted JSON. Not a proper Link Network change JSON.",e)
        case e: MappingException   => reThrow("Incorrectly formatted JSON. Not a proper Link Network change JSON.",e)
        case t: Throwable          => reThrow("Probably incorrectly formatted JSON. Unexpected exception not considered:",t)
      }

    }
  }

  /**
   * Wrapper for persistLinkNetworkChanges(Seq[LinkNetworkChange], ChangeSetMetaData),
   * parsing ChangeSetMetaData from changeSetName, linkDataRetrievalTime, and linkGeomSource.
   *
   * @param changeSet List of link network changes (Seq[ [[LinkNetworkChange]] ]) to be persisted. The change set SHALL NOT CONTAIN
   *                  ONLY PARTIALLY HANDLED LINKS, but the whole length of any link mentioned must be handled completely within the changeSet.
   * @param linkDataRetrievalDate Time (but date accuracy preferred) of the link data retrieval.
 *                                Used as timestamps for the db lines created, that take date as a parameter
   * @param linkGeomSource Source from where the link data / geometries of the set have been
   * @param changeSetName of type [[LinkNetworkChange]]
   * @throws ViiteException if any of the change data is invalid, or incongruent, or there is no such change to be made within Viite
   */
  def persistLinkNetworkChanges(changeSet: Seq[LinkNetworkChange],
                                changeSetName: String,
                                linkDataRetrievalDate: DateTime,
                                linkGeomSource: LinkGeomSource = LinkGeomSource.NormalLinkInterface
                               ): Unit = {
    val changeMetaData = ChangeSetMetaData(changeSetName, linkDataRetrievalDate, linkGeomSource)
    persistLinkNetworkChanges(changeSet, changeMetaData)
  }

  /**
   * Takes in a change set describing dynamic changes to the underlying link network,
   * validates each change, and makes the corresponding changes to the Viite data structures,
   * iff (if and only if) all changes are valid changes for Viite.
   * @note  All of the changes are run within in a single transaction, that either passes or fails.
   *        That is, either the whole changeSet is set as the Viite link network state, or none of it.
   *
   * @param changeSet List of link network changes (Seq[ [[LinkNetworkChange]] ]) to be persisted. The change set SHALL NOT CONTAIN
   *                  ONLY PARTIALLY HANDLED LINKS, but the whole length of any link mentioned must be handled completely within the changeSet.
   * @param changeMetaData see [[ChangeSetMetaData]]
   * @throws ViiteException if any of the change data is invalid, or incongruent, or there is no such change to be made within Viite
   */
  def persistLinkNetworkChanges(changeSet: Seq[LinkNetworkChange], changeMetaData: ChangeSetMetaData): Unit = {

    def persistLinkNetworkChange(change: LinkNetworkChange, changeMetaData: ChangeSetMetaData): Unit = {
      change.changeType match {
        case "replace" => persistReplaceChange(change, changeMetaData)
        case "split"   => persistSplitChange  (change, changeMetaData)
        //case "combine" => combineChange(changeSetName, change)
      }
    }

    LogUtils.time(logger, s"Persist LinkNetworkChanges to Viite data structures, change set '${changeMetaData.changeSetName}'") {
      //try { // TODO Informing MML in case of error?
      withDynTransaction {
        changeSet.foreach(change => persistLinkNetworkChange(change, changeMetaData))
      }
      //}
      //catch { // TODO Informing MML about an error here?
      //  case e: Exception => throw ViiteException(s"An exception when handling dynamic change set $changeSetName:\r${e.getMessage}\r\r")
      //}
    }
  }

  private def persistReplaceChange(change: LinkNetworkChange, changeMetaData: ChangeSetMetaData): Unit = {

    logger.debug("Going to transform to a LinkNetworkReplaceChange")
    val aReplaceChange: LinkNetworkReplaceChange = convertToAValidReplaceChange(change).get // returns or throws
    logger.debug("Transformed to a LinkNetworkReplaceChange")

    // Split linear locations in advance, if the changes do not conform to the current linear locations. So the changes due to link replacements go smoothly.
    Split(aReplaceChange)

    def Split(aReplaceChange: LinkNetworkReplaceChange) {
      aReplaceChange.replaceInfos.foreach( ri => {
        val oldLinearLocations = linearLocationDAO.fetchByLinkIdAndMValueRange(change.oldLink.linkId, ri.oldFromMValue, ri.oldToMValue)
        oldLinearLocations.foreach( oldll => {
          if     ( (oldll.startMValue+GeometryUtils.DefaultEpsilon)<ri.oldFromMValue ) {
            logger.debug(s"Splitting (Replace) ${oldll.id} (${oldll.startMValue}...${oldll.endMValue}) at oldFromMValue ${ri.oldFromMValue}")
            splitLinearLocation(oldll.id, ri.oldFromMValue, changeMetaData)
          }
          else if( (oldll.endMValue-GeometryUtils.DefaultEpsilon)>ri.oldToMValue   ) {
            logger.debug(s"Splitting (Replace) ${oldll.id} (${oldll.startMValue}...${oldll.endMValue}) at oldToMValue ${ri.oldToMValue}")
            splitLinearLocation(oldll.id, ri.oldToMValue,   changeMetaData)
          }
          else {
            logger.debug(s"Splitting (Replace) finished")
          }
        })
      })
    }

    // Make the changes due to link replacements
    LogUtils.time(logger, s"Persist  a Replace change  to Viite data structures (${change.oldLink.linkId}=>${change.newLinks.map(nl => nl.linkId).mkString(", ")})") {
      linkChangesDueToNetworkLinkChange(change, changeMetaData);                      logger.debug("Link created")
      calibrationPointChangesDueToNetworkLinkReplace(aReplaceChange, changeMetaData); logger.debug("CalibrationPoints changed")
      linearLocationChangesDueToNetworkLinkReplace(aReplaceChange, changeMetaData);   logger.debug("Linear locations changed")
    }
  }

  private def splitLinearLocation(linearLocationId: Long, mValueForSplit: Double, changeMetaData: ChangeSetMetaData): Unit = {

    // haetaan toBeSplit lineaarilokaatio lineaarilokaatio id:llä
    val llToBeSplitOption = linearLocationDAO.fetchById(linearLocationId)
    if(llToBeSplitOption.isEmpty) {
      throw ViiteException(s"SplitLinearLocation: linearLocation $linearLocationId could not be found.")
    }
    val llToBeSplit = llToBeSplitOption.get // now should be available; not empty


    // tarkistetaan, että mValue osuu lineaarilokaatioon
    if(mValueForSplit<llToBeSplit.startMValue ||
       mValueForSplit>llToBeSplit.endMValue) {
      throw ViiteException(
        s"SplitLinearLocation: m-value range (${llToBeSplit.startMValue}-${llToBeSplit.endMValue}) " +
        s"of the linearLocation $linearLocationId does not reach $mValueForSplit. Cannot split."
      )
    }

    // tarvitsemme linkin geometrian lineaarilokaation päitä varten
    val llKGVRoadLink //: Seq[DynamicRoadNetworkService.this.kgvClient.roadLinkVersionsData.LinkType]
                         = kgvClient.roadLinkVersionsData.fetchByLinkIds(Set(llToBeSplit.linkId))
    if(llKGVRoadLink.size>1) {  throw ViiteException(
        s"SplitLinearLocation: Found more than one link corresponding to linearLocation $linearLocationId to split. Confused. Cannot split."
    )}
    if(llKGVRoadLink.size==0) {  throw ViiteException(
        s"SplitLinearLocation: No link corresponding to linearLocation $linearLocationId to split found. Cannot split."
    )}
    val splittingPoint: Option[Point] = GeometryUtils.calculatePointFromLinearReference(llKGVRoadLink.head.geometry, mValueForSplit)

    //////// Haetaan kaikki saman roadwayn lineaarilokaatiot, orderNumberien myöhempää päivitystä varten (sorttaa orderNumber-järjestykseen) ////////
    val llsAtTheSameRoadway: Seq[LinearLocation] = linearLocationDAO.fetchByRoadways(Set(llToBeSplit.roadwayNumber)).sortBy(_.orderNumber)

    //////// Luodaan ja tallennetaan uudet lineaarilokaatiot ////////
    //Säädetään splitattujen orderNumberit sideCoden mukaisesti...
    val oldONr = llToBeSplit.orderNumber
    val splittedONrStart = if(llToBeSplit.sideCode==2) oldONr else oldONr+1
    val splittedONrEnd   = if(llToBeSplit.sideCode==2) oldONr+1 else oldONr
println(s"Splitting ll: ${oldONr} (old orderNr) -> ${splittedONrStart}&${splittedONrEnd} (new orderNrs)")
    // ...ja luodaan uudet lineaarilokaatiot jotka vastaavat alkuperäistä katkottuna (alkuM=toBeSplit.startM ... endM=mValue ja alkuM=mValue ... endM=toBeSplit.endM)
    val newStartLL = llToBeSplit.copy(id=NewIdValue, endMValue  =mValueForSplit, orderNumber=splittedONrStart, geometry=Seq(llToBeSplit.geometry.head, splittingPoint.get)) //TODO get may fail
    val newEndLL   = llToBeSplit.copy(id=NewIdValue, startMValue=mValueForSplit, orderNumber=splittedONrEnd,   geometry=Seq(splittingPoint.get, llToBeSplit.geometry.last)) //TODO get may fail
    // tallennetaan splitatut ja ekspiroidaan vanha lineaarilokaatio
    linearLocationDAO.create(Seq(newStartLL), changeMetaData.changeSetName)
    linearLocationDAO.create(Seq(newEndLL),   changeMetaData.changeSetName)
    linearLocationDAO.expireByIds(Set(llToBeSplit.id))

    //////// Säädetään loput OrderNumberit huomioimaan väliin tulleen uuden lineaarilokaation ////////
    // generoi roadwayn lineaarilokaatioille uudet järjestysnumerot; yhtä suuremmat kuin tähän mennessä, koska yksi tuli lisää
    val roadwayLlsPlusNewOrdNums = llsAtTheSameRoadway.zip(List.range(2, llsAtTheSameRoadway.size+1))
    // jätä orderNumber-päivitettäväksi vain ne lineaarilokaatiot, jotka ovat roadwaylla splitatun lineaarilokaation jälkeen
    val roadwayLlsFartherAway: Seq[(LinearLocation, Int)] = roadwayLlsPlusNewOrdNums.filter(_._1.orderNumber>=llToBeSplit.orderNumber + 2)
roadwayLlsFartherAway.foreach(asdf => println(s"${asdf._1.orderNumber} -> ${asdf._2}  "))
    // tallennetaan lineaarilokaatiot, joiden orderNumber muuttui, ja ekspiroidaan vanhat
    roadwayLlsFartherAway.foreach(ll => {
      val LLWithNewOrderNumber = ll._1.copy(id=NewIdValue, orderNumber=ll._2)
      linearLocationDAO.create(Seq(LLWithNewOrderNumber), changeMetaData.changeSetName)
      linearLocationDAO.expireByIds(Set(ll._1.id))
    })

  }

  private def persistSplitChange(change: LinkNetworkChange, changeMetaData: ChangeSetMetaData): Unit = {

    logger.debug("Going to transformed to a LinkNetworkSplitChange")
    val aSplitChange: LinkNetworkSplitChange = convertToAValidSplitChange(change).get // returns or throws
    logger.debug("Transformed to a LinkNetworkSplitChange")

    // Split linear locations in advance, if the changes do not conform to the current linear locations. So the changes due to link replacements go smoothly.
    Split(aSplitChange)

    def Split(aSplitChange: LinkNetworkSplitChange) {
      aSplitChange.replaceInfos.foreach( ri => {
        val oldLinearLocations = linearLocationDAO.fetchByLinkIdAndMValueRange(change.oldLink.linkId, ri.oldFromMValue, ri.oldToMValue)
        oldLinearLocations.foreach( oldll => {
          if     ( (oldll.startMValue+GeometryUtils.DefaultEpsilon)<ri.oldFromMValue ) {
            logger.debug(s"Splitting (Split) ${oldll.id} (${oldll.startMValue}...${oldll.endMValue}) at oldFromMValue ${ri.oldFromMValue}")
            splitLinearLocation(oldll.id, ri.oldFromMValue, changeMetaData)
          }
          else if( (oldll.endMValue  -GeometryUtils.DefaultEpsilon)>ri.oldToMValue   ) {
            logger.debug(s"Splitting (Split) ${oldll.id} (${oldll.startMValue}...${oldll.endMValue}) at oldToMValue ${ri.oldToMValue}")
            splitLinearLocation(oldll.id, ri.oldToMValue,   changeMetaData)
          }
          else {
            logger.debug(s"Splitting (Split) finished")
          }
        })
      })
    }
    LogUtils.time(logger, s"Make a Split change to Viite data structures (${change.oldLink.linkId}=>${change.newLinks.map(nl => nl.linkId).mkString(", ")}") {
      linkChangesDueToNetworkLinkChange(change, changeMetaData);                  logger.debug("Link created")
      calibrationPointChangesDueToNetworkLinkSplit(aSplitChange, changeMetaData); logger.debug("CalibrationPoints changed")
      linearLocationChangesDueToNetworkLinkSplit(aSplitChange, changeMetaData);   logger.debug("Linear locations changed")
    }
  }

  /**
   * Converts the given LinkNetworkChange <i>change</i>, to a LinkNetworkSplitChange, if it has
   * changeType "split", and it passes the validations for a proper LinkNetworkSplitChange.
   *
   * @param change the LinkNetworkChange to be returned as a LinkNetworkSplitChange
   * @return a valid LinkNetworkSplitChange, or None, if changeType was not "replace"
   * @throws ViiteException when the given <i>change</i> (of changeType "split") is not a valid
   *                        LinkNetworkSplitChange, but it has structural, or logical flaws.
   */
  private def convertToAValidSplitChange(change: LinkNetworkChange): Option[LinkNetworkSplitChange] = {

    // Validate the change for a split change; get out with a throw if the change is not a valid split change

    if(!change.changeType.equals("split")) {
      throw ViiteException(s"LinkNetworkChange: Shall not try to convert a change of type ${change.changeType} to a LinkNetworkSplitChange. " +
        s"Check the parameter of the calling function.")
    }

    logger.debug("size considerations")
    if ( change.newLinks.size < 2
      || change.replaceInfos.size < change.newLinks.size // at least one replaceInfo per a new link
      //|| change.newLinks.size != change.replaceInfos.size
    ) {
      throw ViiteException(s"LinkNetworkChange: Invalid SplitChange. A split must have at least " +
        s"two new links, and their corresponding replace infos (who may be further splitted to smaller chunks). " +
        s"There are ${change.newLinks.size} new links, and ${change.replaceInfos.size} replace infos " +
        s"when going to split ${change.oldLink.linkId}.")
    }

    // Ok, now we know we have a proper amount of components
    val oldLink = change.oldLink
    val newLinks = change.newLinks
    val splitInfos = change.replaceInfos

    logger.debug("data integrity: link ids")
    if (!splitInfos.forall(si => oldLink.linkId == si.oldLinkId)) {
      throw ViiteException(s"LinkNetworkChange: Invalid SplitChange. The old link ${oldLink.linkId} must be part of all of the replace infos. ")
    }
    splitInfos.foreach(si =>
      if(!newLinks.exists(nl => nl.linkId==si.newLinkId)) {
        throw ViiteException(s"LinkNetworkChange: Invalid SplitChange. The new link info required by the replace info link ${si.newLinkId} is not available in the SplitChange data. ")
      }
    )

    logger.debug(s"data integrity: told lengths must match sufficiently. Allowed difference: ${GeometryUtils.DefaultEpsilon} m ")
    val oldLengthFromOldLink    = GeometryUtils.scaleToThreeDigits(oldLink.linkLength) // may already be rounded to three digits, but whatever
    val oldLengthFromSplitInfos = GeometryUtils.scaleToThreeDigits(splitInfos.foldLeft(0.0)((cumulLength,splitInfo) => cumulLength + splitInfo.oldToMValue-splitInfo.oldFromMValue))
    val newLengthFromNewLinks   = GeometryUtils.scaleToThreeDigits(newLinks  .foldLeft(0.0)((cumulLength,splitInfo) => cumulLength + splitInfo.linkLength))
    val newLengthFromSplitInfos = GeometryUtils.scaleToThreeDigits(splitInfos.foldLeft(0.0)((cumulLength,splitInfo) => cumulLength + splitInfo.newToMValue-splitInfo.newFromMValue))

    if (oldLengthFromOldLink  != oldLengthFromSplitInfos) {  // old link lengths must always match
      throw ViiteException(s"LinkNetworkChange: Invalid SplitChange. Old link lengths do not match when splitting link $oldLink." +
        s"Check that lengths (oldToMValue-oldFromMValue) in the replaceInfos (now $oldLengthFromSplitInfos) " +
        s"sum up to that of the old link length ($oldLink.linkLength)")
    }
    //if (newLengthFromNewLinks != newLengthFromSplitInfos) { // new link lengths must always match //TODO NO! Does not apply to Splits. New links may be splitted to multiple old links, and thus multiple changes. If wish to check this, must be checked at upper levels.
    //  throw ViiteException(s"LinkNetworkChange: Invalid SplitChange. New link lengths do not match when splitting link $oldLink." +
    //    s"Check that lengths (newToMValue-newFromMValue) in the replaceInfos (now $newLengthFromSplitInfos) " +
    //    s"sum up to that of the lengths of the new links ($newLengthFromNewLinks).")
    //}

    logger.debug(s"data integrity: geometry requirements")
    if(oldLink.geometry.size<2) {
      throw ViiteException(s"LinkNetworkChange: Invalid old link geometry. " +
        s"A geometry must have at least two points. ${oldLink.linkId}: ${oldLink.geometry}")
    }
    newLinks.foreach(
      nl =>  if(nl.geometry.size<2) {
        throw ViiteException(s"LinkNetworkChange: Invalid new link geometry. " +
          s"A geometry must have at least two points. ${nl.linkId}: ${nl.geometry}")
      }
    )

    //TODO link.geometry vs. link.linkLength checks?

    logger.debug("data integrity: digitization")
    splitInfos.foreach(ri =>
      if (ri.digitizationChange) {
        // TODO is digitizationChange always ok as is, or do we have to check something when it is true?
      }
    )

    logger.debug("Validations for a split change passed, returning a proper LinkNetworkSplitChange")
    Some(LinkNetworkSplitChange(oldLink, newLinks, splitInfos))

  }

  /**
   * Converts the LinkNetworkChange <i>change</i>, to a LinkNetworkReplaceChange, if it has
   * changeType "replace", and it passes the validations for a proper LinkNetworkReplaceChange.
   *
   * @param change the LinkNetworkChange to be returned as a LinkNetworkReplaceChange
   * @return a valid LinkNetworkReplaceChange, or None, if changeType was not "replace"
   * @throws ViiteException when the given <i>change</i> (of changeType "replace") is not a valid
   *                        LinkNetworkReplaceChange, but it has structural, or logical flaws.
   */
  private def convertToAValidReplaceChange(change: LinkNetworkChange): Option[LinkNetworkReplaceChange] = {

    // Validate the change for a replace change; get out with a throw if the change is not a valid replace change

    if(!change.changeType.equals("replace")) {
      throw ViiteException(s"LinkNetworkChange: Shall not try to convert a change of type ${change.changeType} to a LinkNetworkReplaceChange. " +
        s"Check the parameter of the calling function.")
    }

    logger.debug("size considerations")
    if (change.newLinks.size != 1) {
      throw ViiteException(s"LinkNetworkChange: Invalid ReplaceChange. A replace must have exactly one new link. " +
        s"There are ${change.newLinks.size} new links when going to replace ${change.oldLink.linkId}.")
    }
    if (change.replaceInfos.isEmpty) {
      throw ViiteException(s"LinkNetworkChange: Invalid ReplaceChange. A replace must have at least one replaceInfo. " +
        s"There is no replace infos when going to replace ${change.oldLink.linkId}.")
    }
    // Ok, we know we have a proper amount of components
    val oldLink = change.oldLink
    val newLink = change.newLinks.head
    val replaceInfos = change.replaceInfos

    logger.debug("data integrity: link ids")
    if (!replaceInfos.forall(ri => oldLink.linkId == ri.oldLinkId)) {
      throw ViiteException(s"LinkNetworkChange: Invalid ReplaceChange. The old link ${oldLink.linkId} must be part of all of the replace infos. ")
    }
    if(!replaceInfos.forall(r => newLink.linkId==r.newLinkId)) {
      throw ViiteException(s"LinkNetworkChange: Invalid ReplaceChange. The new link ${newLink.linkId} must be part of all of the replace infos. ")
    }

    logger.debug(s"data integrity: told lengths must match sufficiently. Allowed difference: ${GeometryUtils.DefaultEpsilon} m ")
    val oldLinLocLengths = replaceInfos.map(ri => (ri.oldToMValue-ri.oldFromMValue).abs)
    val newLinLocLengths = replaceInfos.map(ri => (ri.newToMValue-ri.newFromMValue).abs)
    val sumOfOldLengths = oldLinLocLengths.foldLeft(0.0)( (cumul,next) => cumul + next )
    val sumOfNewLengths = newLinLocLengths.foldLeft(0.0)( (cumul,next) => cumul + next )
    val oldlinkOK = linkLengthsConsideredTheSame(oldLink.linkLength, sumOfOldLengths)       // old link, and replaceInfo lengths must match (resolution: GeometryUtils.DefaultEpsilon).
    val newlinkOK = linkLengthsConsideredTheSame(newLink.linkLength, sumOfNewLengths) ||    // new link, and replaceInfo lengths must match (resolution: GeometryUtils.DefaultEpsilon) ...
                     GeometryUtils.scaleToThreeDigits(newLink.linkLength) > sumOfNewLengths //... or replaceInfo lengths be smaller than new link length, when new link continues within another oldLink.
    if (!oldlinkOK || !newlinkOK) {
      throw ViiteException(s"LinkNetworkChange: Invalid ReplaceChange. Link lengths of the ReplaceChange do not match. Check\r" +
        s"(old link ${oldLink.linkId}: ${oldLink.linkLength} vs. ${oldLinLocLengths.mkString("+")}=$sumOfOldLengths), and\r " +
        s"(new link ${newLink.linkId}: ${newLink.linkLength} vs. ${newLinLocLengths.mkString("+")}=$sumOfNewLengths).")
    }

    logger.debug(s"data integrity: geometry requirements")
    if(oldLink.geometry.size<2) {
      throw ViiteException(s"LinkNetworkChange: Invalid old link geometry. " +
        s"A geometry must have at least two points. ${oldLink.linkId}: ${oldLink.geometry}")
    }
    if(newLink.geometry.size<2) {
      throw ViiteException(s"LinkNetworkChange: Invalid new link geometry. " +
        s"A geometry must have at least two points. ${newLink.linkId}: ${newLink.geometry}")
    }

    //TODO link.geometry vs. link.linkLength checks?

    logger.debug("data integrity: digitization")
    replaceInfos.foreach(
      ri => if (ri.digitizationChange) {
      // TODO is digitizationChange always ok as is, or do we have to check something when it is true?
      }
    )

    logger.debug("Validations for a replace change passed, returning a proper LinkNetworkReplaceChange")
    Some(LinkNetworkReplaceChange(oldLink, newLink, replaceInfos))
  }

  /**
   * Invalidates the old, and created new linear locations according to the given split <i>change</i>.
   *
   * @param change        The link network change whose related changes we want to make to the database.
   * @param changeMetaData changeSetName used as the creator (createdBy) for the linearLocations created.
   * @return              Sequence of ids of the created linearLocations
   */
  private def linearLocationChangesDueToNetworkLinkSplit(change: LinkNetworkSplitChange, changeMetaData: ChangeSetMetaData): Unit = {

    change.replaceInfos.foreach(ri => {
      val oldLinearLocations = linearLocationDAO.fetchByLinkIdAndMValueRange(change.oldLink.linkId, ri.oldFromMValue, ri.oldToMValue)
println()
oldLinearLocations.foreach(ll => println(s"${ll}"))

      if(oldLinearLocations.isEmpty) {
          throw ViiteException(s"LinkNetworkReplaceChange: No old linear location found for link ${change.oldLink.linkId}.")
      }

      val oldLlsSorted = oldLinearLocations.sortBy(_.startMValue)
      if(!linkLengthsConsideredTheSame(ri.oldFromMValue, oldLlsSorted.head.startMValue)) {
        throw ViiteException(s"LinkNetworkReplaceChange: start values do not match sufficiently: ${ri.oldFromMValue} vs. ${oldLlsSorted.head.startMValue}.")
      }
      if(!linkLengthsConsideredTheSame(ri.oldToMValue, oldLlsSorted.last.endMValue)  ) {
        throw ViiteException(s"LinkNetworkReplaceChange:  end  values do not match sufficiently: ${ri.oldToMValue}   vs. ${oldLlsSorted.last.endMValue}."  )
      }

      oldLlsSorted.foreach(oldLL => { // make changes linearlocation wise. Usually there is only one. But might be many.

        var newLlStartMValue = GeometryUtils.getProjectedValue(ri.oldFromMValue, ri.oldToMValue, ri.newFromMValue, ri.newToMValue, oldLL.startMValue)
        var newLlEndMValue   = GeometryUtils.getProjectedValue(ri.oldFromMValue, ri.oldToMValue, ri.newFromMValue, ri.newToMValue, oldLL.endMValue  )

        val linkGeomForReplaceInfo = change.newLinks.find(_.linkId == ri.newLinkId).get.geometry
        val minMValuePointOpt = GeometryUtils.calculatePointFromLinearReference(linkGeomForReplaceInfo, newLlStartMValue) // TODO snap to geometry points? Check not overflowing the link length?
        val maxMValuePointOpt = GeometryUtils.calculatePointFromLinearReference(linkGeomForReplaceInfo, newLlEndMValue)   // TODO snap to geometry points? Check not overflowing the link length?
        if(minMValuePointOpt.isEmpty || maxMValuePointOpt.isEmpty) { // check that we got'em all
          ViiteException(s"LinkNetworkReplaceChange: Could not get a corresponding point for either of both ends of the " +
            s"new linear location referring to the new link  ${change.newLinks.find(_.linkId == ri.newLinkId).get.linkId}.")
        }

        val newLL = LinearLocation(
          NewIdValue,          //id: Long,
          oldLL.orderNumber,   //orderNumber: Double,      //note: handling of the orderNumber handled by splitting the linear locations in advance
          ri.newLinkId,        //linkId: String,
          newLlStartMValue,    //startMValue: Double, //ri.newFromMValue would do, if there always were only one Viite old ll corresponding to a replaceInfo
          newLlEndMValue,      //endMValue:   Double, //ri.newToMValue   would do, if there always were only one Viite old ll corresponding to a replaceInfo
          decideNewSideCode(ri.digitizationChange, oldLL.sideCode),
          0,                   //adjustedTimestamp: Long, // Not required, link created elsewhere
          (CalibrationPointReference.None, CalibrationPointReference.None), //CPs created elsewhere //calibrationPoints: (CalibrationPointReference, CalibrationPointReference) = (CalibrationPointReference.None, CalibrationPointReference.None)
          Seq(minMValuePointOpt.get.with3decimals, maxMValuePointOpt.get.with3decimals), //geometry: Seq[Point]
          //Seq(correspondingLink.geometry.head, correspondingLink.geometry.last), //geometry: Seq[Point]
          LinkGeomSource.Unknown, // Not required, link created elsewhere
          oldLL.roadwayNumber,    //roadwayNumber: Long
          None, // Not used at create         //validFrom: Option[DateTime] = None
          None  // Not used at create         //validTo:   Option[DateTime] = None
        ) // LinearLocation

        linearLocationDAO.create(Seq(newLL), changeMetaData.changeSetName)
        //val llIds = oldLinearLocations.map(_.id).toSet
        /*val numInvalidatedLLs: Int =*/ linearLocationDAO.expireByIds(Set(oldLL.id))  //(llIds)
      }) // oldLlsSorted.foreach
    }) // change.replaceInfo.foreach
  }

  private def calibrationPointChangesDueToNetworkLinkSplit(change: LinkNetworkSplitChange, changeMetaData: ChangeSetMetaData): Seq[(Long, Long)] = { // TODO RETURN id mapper list for old-new CPs?
    val oldLinkId = change.oldLink.linkId
    val CPsOfOldLink: Seq[CalibrationPoint] = CalibrationPointDAO.fetchByLinkId(Seq(oldLinkId))  // There might be none, and that is ok.
    val startCP: Option[CalibrationPoint] = CPsOfOldLink.find(cp => cp.startOrEnd==StartOfLink)
    val endCP:   Option[CalibrationPoint] = CPsOfOldLink.find(cp => cp.startOrEnd==EndOfLink  )

    var idPairs: Seq[(Long, Long)] = Seq()

    // create corresponding calibration points for new links, based on the old calibration points
    val linkStartInfo: Option[ReplaceInfo] = change.replaceInfos.find(ri => ri.oldFromMValue == 0)  // TODO is this reversed, if addresses grow in the opposite direction?
    val linkEndInfo:   Option[ReplaceInfo] = change.replaceInfos.find(ri => ri.oldToMValue == change.oldLink.linkLength)

    if(startCP.isDefined) {
      val newStartCP = startCP.get.copy(
        id = NewIdValue,
        linkId = linkStartInfo.get.newLinkId,
        createdBy = changeMetaData.changeSetName,
        createdTime = Some(changeMetaData.linkDataRetrievalDate)
      )
      val newStartCPid = CalibrationPointDAO.create(Seq(newStartCP)).head // we know we have only one
      idPairs = idPairs :+ (newStartCP.id, newStartCPid)
    }

    if(endCP.isDefined) {
      val newEndCP = endCP.get.copy(
        id = NewIdValue,
        linkId = linkEndInfo.get.newLinkId,
        createdBy = changeMetaData.changeSetName,
        createdTime = Some(changeMetaData.linkDataRetrievalDate)
      )
      val newEndCPid = CalibrationPointDAO.create(Seq(newEndCP)).head // we know we have only one
      idPairs = idPairs :+ (newEndCP.id, newEndCPid)
    }

    // expire old calibration points, referring to the old link
    CalibrationPointDAO.expireById(CPsOfOldLink.map(_.id))

    idPairs
  }

  /**
   * Invalidates the old, and created new linear locations according to the given replace <i>change</i>.
   *
   * @param change         The link network change whose related changes we want to make to the database.
   * @param changeMetaData changeSetName used as the creator (createdBy) for the linearLocations created.
   * @return               Sequence of ids of the created linearLocations
   */
  private def linearLocationChangesDueToNetworkLinkReplace(change: LinkNetworkReplaceChange, changeMetaData: ChangeSetMetaData) = {

    change.replaceInfos.foreach(ri => { // make changes Tiekamu change by Tiekamu change

      val oldLinearLocations = linearLocationDAO.fetchByLinkIdAndMValueRange(change.oldLink.linkId, ri.oldFromMValue, ri.oldToMValue)
      if(oldLinearLocations.isEmpty) {
          throw ViiteException(s"LinkNetworkReplaceChange: No old linear location found for link ${change.oldLink.linkId}.")
      }

      val oldLlsSorted = oldLinearLocations.sortBy(_.startMValue)
      //tarkista, että ri:n ja oldll:ien tiedot täsmäävät
      if(!linkLengthsConsideredTheSame(ri.oldFromMValue, oldLlsSorted.head.startMValue)) {
        throw ViiteException(s"LinkNetworkReplaceChange: start values do not match sufficiently: ${ri.oldFromMValue} vs. ${oldLlsSorted.head.startMValue}.")
      }
      if(!linkLengthsConsideredTheSame(ri.oldToMValue, oldLlsSorted.last.endMValue)  ) {
        throw ViiteException(s"LinkNetworkReplaceChange:  end  values do not match sufficiently: ${ri.oldToMValue}   vs. ${oldLlsSorted.last.endMValue}."  )
      }

      oldLlsSorted.foreach(oldLL => { // make changes linearlocation wise. Usually there is only one. But might be many.

        var newLlStartMValue = GeometryUtils.getProjectedValue(ri.oldFromMValue, ri.oldToMValue, ri.newFromMValue, ri.newToMValue, oldLL.startMValue)
        var newLlEndMValue   = GeometryUtils.getProjectedValue(ri.oldFromMValue, ri.oldToMValue, ri.newFromMValue, ri.newToMValue, oldLL.endMValue  )

        val minMValuePointOpt = GeometryUtils.calculatePointFromLinearReference(change.newLink.geometry, newLlStartMValue) // TODO snap to geometry points? Check not overflowing the link length?
        val maxMValuePointOpt = GeometryUtils.calculatePointFromLinearReference(change.newLink.geometry, newLlEndMValue)   // TODO snap to geometry points? Check not overflowing the link length?
        if(minMValuePointOpt.isEmpty || maxMValuePointOpt.isEmpty) { // check that we got'em all
          ViiteException(s"LinkNetworkReplaceChange: Could not get a corresponding point for either of both ends of the " +
            s"new linear location referring to the new link  ${change.newLink.linkId}.")
        }


        val newLL = LinearLocation(
          NewIdValue,             //id:          Long,
          oldLL.orderNumber,      //orderNumber: Double,      //note: handling of the orderNumber handled by splitting the linear locations in advance
          change.newLink.linkId,  //linkId: String,
          newLlStartMValue,       //startMValue: Double,      //ri.newFromMValue would do, if there always were only one Viite old ll corresponding to a replaceInfo
          newLlEndMValue,         //endMValue:   Double,      //ri.newToMValue   would do, if there always were only one Viite old ll corresponding to a replaceInfo
          decideNewSideCode(ri.digitizationChange, oldLL.sideCode),
          0,                      //adjustedTimestamp: Long,  // Not required, link created elsewhere
          (CalibrationPointReference.None, CalibrationPointReference.None), //CPs created elsewhere //calibrationPoints: (CalibrationPointReference, CalibrationPointReference) = (CalibrationPointReference.None, CalibrationPointReference.None)
          Seq(minMValuePointOpt.get.with3decimals, maxMValuePointOpt.get.with3decimals), //geometry: Seq[Point]
          LinkGeomSource.Unknown, // Not required, link created elsewhere
          oldLL.roadwayNumber,    //roadwayNumber: Long
          None,          //validFrom: Option[DateTime] = None  // Not used at create (why?)
          None           //validTo:   Option[DateTime] = None  // Not used at create (why?)
        )

        linearLocationDAO.create(Seq(newLL), changeMetaData.changeSetName)
        //val llIds = oldLinearLocations.map(_.id).toSet
        /*val numInvalidatedLLs: Int =*/ linearLocationDAO.expireByIds(Set(oldLL.id))  //(llIds)
      }) // oldLlsSorted.foreach
    }) // change.replaceInfos.foreach
  }

  /**
   * Create new calibration points, and expire the old ones corresponding to the old link.
   *
   * @param change replace change about the replace
   * @param changeMetaData .changeSetName, and .linkDataRetrievalTime used as calibration point creation data
   * @return List of (old cp-id, new cp-id) pairs, where old ones were expired, and new ones created,
   */
  private def calibrationPointChangesDueToNetworkLinkReplace(change: LinkNetworkReplaceChange, changeMetaData: ChangeSetMetaData): Seq[(Long, Long)] = {    //Return id mapper list for old-new CPs
    val oldLinkId = change.oldLink.linkId
    val CPsOfOldLink = CalibrationPointDAO.fetchByLinkId(Seq(oldLinkId)) // There might be none, and that is ok.

    var idPairs: Seq[(Long, Long)] = Seq()

    // create corresponding calibration points, based on the old calibration points
    CPsOfOldLink.foreach(oldCP => {
      val newCP = oldCP.copy(
        id = NewIdValue,
        linkId = change.newLink.linkId,
        createdBy = changeMetaData.changeSetName,
        createdTime = Some(changeMetaData.linkDataRetrievalDate)
      )
      val newCPid = CalibrationPointDAO.create(newCP)
      idPairs = idPairs:+(oldCP.id,newCPid)
    })

    // expire old calibration points, referring to the old link
    CalibrationPointDAO.expireById(CPsOfOldLink.map(_.id))

    idPairs
  }

  /**
   * Creates new links that correspond to the old link, according to the given link network change object.
   * (A new link that already exists in Viite, however, is not created. :) )
   *
   * @param change A single change object from the set of link network changes.
   *               change.oldLink must be a link existing in Viite, and then the links stated by change.newLinks can be created.
   *               The link existence in the KGV, or other external resources is NOT CHECKED, but the new links' ids are
   *               assumed to be correct.
   * @param changeMetaData Uses changeMetaData.changeMetaDatageomSource as information telling where the link information
   *                       has been retrieved from. Uses changeMetaData.linkDataRetrievalDate as the link.adjustedTimestamp;
   *                       the time when the link informationhas been retrieved from the link source.
   * @throws ViiteException when the link id of the old link is not found within Viite.
   */
  private def linkChangesDueToNetworkLinkChange(change: LinkNetworkChange, changeMetaData: ChangeSetMetaData): Unit = {
    val oldLinkId = change.oldLink.linkId

    if (LinkDAO.fetch(oldLinkId).isEmpty) {
      throw ViiteException(s"LinkNetworkChange: No old link $oldLinkId found on road network")
    }
    logger.debug("Old link available")

    if (change.newLinks.isEmpty) {
      throw ViiteException(s"LinkNetworkChange: No new links available when trying to replace $oldLinkId")
    }
    logger.debug("New link data available")

    change.newLinks.foreach(nl => {
      logger.debug(s"Creating link ${nl.linkId}")
      LinkDAO.createIfEmptyFetch(
        nl.linkId,
        changeMetaData.linkDataRetrievalDate.getMillis,
        changeMetaData.linkGeomSource.value  // TODO Would prefer name of the change set, but oh well. The db does not take in that kind of data.
      )
     }
    )
    logger.debug("New links created")
  }

  private def decideNewSideCode(digitizationChanged: Boolean, oldSideCode: SideCode) = {
    if (digitizationChanged) SideCode.switch(oldSideCode) else oldSideCode
  }

  /**
   * When given an old link, and two measured values to define a range within the old link,
   * returns values that percent wise correspond to those measured values on the new link.
   *
   * <pre>
   *                 old link, length 25
   * |- - - - -|- - - - - - - - - -|- - - - - - - - - -|
   * 00       05                   15                 25
   *      oldMinMValue        oldMaxMValue
   *          20%                  60%
   *
   *                 new link, length 30
   * |- - - - - -|- - - - - - - - - - - -|- - - - - - - - - - - -|
   * 00         06                      18                     30
   *      newLinkMinMvalue        newLinkMaxMValue
   *            20%                     60%
   * </pre>
   *
   * @param oldLink The old link defining the possible range of the original m values (0...linkLength)
   * @param oldMinMValue The smaller of the M values, whose location on the new link is to be returned
   * @param oldMaxMValue The bigger of the M values, whose location on the new link is to be returned
   * @param newLink The new link defining the possible range of the returned m values
   * @throws ViiteException if given oldMinMValue, or oldMaxMValue falls outside of the old link range
   *
   * @todo Snapping to oldLink geometry points, if given mvalues are close enough? (Less than GeometryUtils.DefaultEpsilon away?)
   */
  private def getCorrespondingNewLinkMvalueRange(oldLink: LinkInfo, oldMinMValue: Double, oldMaxMValue: Double,
                                                 newLink: LinkInfo, newMinMValue: Double, newMaxMValue: Double): (Double, Double) = {

    if(oldMinMValue<0 || oldMinMValue>oldLink.linkLength) {
      ViiteException(s"oldMinMValue (now $oldMinMValue) may not refer outside the length of the link (0...${oldLink.linkLength}).")
    }
    if(oldMaxMValue<0 || oldMaxMValue>oldLink.linkLength) {
      ViiteException(s"oldMaxMValue (now $oldMaxMValue)  may not refer outside the length of the link (0...${oldLink.linkLength}).")
    }
    if(newMinMValue<0 || newMinMValue>newLink.linkLength) {
      ViiteException(s"newMinMValue (now $newMinMValue) may not refer outside the length of the link (0...${newLink.linkLength}).")
    }
    if(newMaxMValue<0 || newMaxMValue>newLink.linkLength) {
      ViiteException(s"newMaxMValue (now $newMaxMValue)  may not refer outside the length of the link (0...${newLink.linkLength}).")
    }

    val minMValuePercentage = oldMinMValue/oldLink.linkLength
    val maxMValuePercentage = oldMaxMValue/oldLink.linkLength
    val newLinkMinMvalue = minMValuePercentage*newLink.linkLength*newMinMValue
    val newLinkMaxMValue = maxMValuePercentage*newLink.linkLength*newMaxMValue

    (newLinkMinMvalue, newLinkMaxMValue)
  }

  /** Tell if the given length measures are considered to be the same.
   * Tolerance is [[GeometryUtils.DefaultEpsilon]].
   */
  private def linkLengthsConsideredTheSame(l1: Double, l2: Double): Boolean = {
    (l1-l2).abs<GeometryUtils.DefaultEpsilon
  }
}