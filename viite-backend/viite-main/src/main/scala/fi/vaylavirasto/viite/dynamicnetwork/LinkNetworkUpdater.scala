package fi.vaylavirasto.viite.dynamicnetwork

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonParseException
import fi.liikennevirasto.digiroad2.util.LogUtils
import fi.liikennevirasto.viite.NewIdValue
import fi.liikennevirasto.viite.dao.{CalibrationPointDAO, CalibrationPointReference, LinearLocation, LinearLocationDAO}
import fi.vaylavirasto.viite.dao.LinkDAO
import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.model.CalibrationPointLocation.{EndOfLink, StartOfLink}
import fi.vaylavirasto.viite.model.{CalibrationPoint, LinkGeomSource, SideCode}
import fi.vaylavirasto.viite.postgis.PostGISDatabase
import fi.vaylavirasto.viite.util.ViiteException
import org.joda.time.DateTime
import org.json4s.jackson.JsonMethods
import org.json4s.{DefaultFormats, MappingException}
import org.slf4j.{Logger, LoggerFactory}

/**
 * Translates the dynamic link network change sets to proper Viite data changes, and persists them to the database.
 * @see [[persistLinkNetworkChanges]], and
 *
 * Viite accepts two types of changes; replace, and split. //TODO How about combine? Is it different from these two?
 * All of the incoming changes are supposed to keep network address topology intact.
 * That is, no road address shall be changed due to any link network change, and the nodes, and junctions
 * must also have their relative positions after the changes.
 */


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
 * @param linkLength (2D) Geometry length of the whole link, from start (0) to the other end of the geometry.
 * @param geometry  //TODO end points, or the whole geometry of the link? Or whatever? Do we need to interpolate? At least two points. 2D? 3D?
 */
case class LinkInfo(linkId: String,
                    linkLength: Double,
                    geometry: Seq[Point] // TODO Point ok for the type?
                    )
/**
 * Replace info, telling which part of the current ("old") link gets replaced with which part of the replacing ("new") link.
 *
 * @param oldLinkId     Id of the link to be replaced with new link(s). Link data must be found in the oldlink link data.
 * @param oldFromMValue Dictates the first geometry point of the old link, where the old link is to be replaced with the new link
 *                      Usually 0. Bigger value legit only when splitting the old link,
 *                      and this replace is other than the first part of it. Smaller never.
 * @param oldToMValue   Dictates the last  geometry point of the old link, where the old link is to be replaced with the new link
 *                      Usually the same as geometry length of the old link. Smaller value legit only when splitting the old link,
 *                      and this replace is other than the last part of it. Bigger never.
 * @param newLinkId     Id of the link to be replacing the old link. Link data must be found in the newLinks link data.
 * @param newFromMValue Dictates the first geometry point of the new link, where the new link replaces old link.
 *                      Usually 0. Bigger value legit only when combining old links,
 *                      and this replace is other than the first part of the combine. Smaller never.
 * @param newToMValue   Dictates the last  geometry point of the new link, where the new link replaces old link.
 *                      Usually the same as geometry length of the new link. Smaller value legit only when combining old links,
 *                      and this replace is other than the last part of the combine. Bigger never.
 * @param digitizationChange Tells, whether the drawing direction of the new link is opposite of the old link, or just
 *                           the same as before. True for opposite direction, false for staying the same.
 */
case class ReplaceInfo( oldLinkId: String, oldFromMValue: Double, oldToMValue: Double,
                        newLinkId: String, newFromMValue: Double, newToMValue: Double,
                        digitizationChange: Boolean
                       )
/**
 * A generic network link change type, for reading the whole JSON ("samuutussetti") in.
 *
 * @note excess JSONArray braces required due to JsonMethods.parse functionality. Correct, when the library allows.
 * (See [[persistLinkNetworkChanges]].)
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
 * @param oldLink Data describing the old link in the link network, before the change.
 * @param newLinks Data describing the new links that replace the oldLink in the updated link network.
 * @param replaceInfo Data describing the relation (correspondence) between the oldLink, and the newLinks.
 */
case class LinkNetworkChange(changeType:  String,
                             oldLink:     LinkInfo,
                             newLinks:    Seq[LinkInfo],
                             replaceInfo: Seq[ReplaceInfo]
                            )
/** A NetworkLink change of type "replace". Use only for validated, proper changes of type "replace".
 * Use function convertToAValidReplaceChange for validation, and conversion. */
private case class LinkNetworkReplaceChange(oldLink: LinkInfo,
                                            newLink: LinkInfo,
                                            replaceInfo: ReplaceInfo
                                           )
/** A NetworkLink change of type "split". Use only for validated, proper changes of type "split".
 * Use function convertToAValidSplitChange for validation, and conversion. */
private case class LinkNetworkSplitChange(oldLink:     LinkInfo,
                                          newLinks:    Seq[LinkInfo],
                                          replaceInfo: Seq[ReplaceInfo]
                                         )

/**
 * Metadata common to every change within the single call of [[persistLinkNetworkChanges]]
 *
 * @param changeSetName  Name of the change set, whose data is to be saved. Used as
 *                       data creator's "name", and for logging / throw messages.
 * @param linkDataRetrievalTime Time (date preferred) of the link data retrieval.
 *                              Used as timestamps for the db lines created, who take date as a parameter
 * @param linkGeomSource Source from where the link data / geometries of the set have been retrieved
 */
case class ChangeSetMetaData(changeSetName: String,
                             linkDataRetrievalDate: DateTime,
                             linkGeomSource: LinkGeomSource
                            )

class LinkNetworkUpdater {

  val logger: Logger = LoggerFactory.getLogger(getClass)
  private implicit val formats: DefaultFormats.type = DefaultFormats // json4s requires this for extract, and extractOpt

  // define here, to be able to override from spec file
  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)


  private val linearLocationDAO = new LinearLocationDAO // LinearLocationDAO is a class, fot testing possibility (must be able to override db functionality)

  /**
   * Takes in a change set JSON, and extracts a list of [[LinkNetworkChange]]s out of it.
   *
   * @param changeSetJSON JSON containing dynamic link network changes, of type Seq[ [[LinkNetworkChange]] ]
   * @param changeSetName Name of the set the given JSON has been received from. Used for logging.
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
   * @param changeSet List of link network changes (Seq[ [[LinkNetworkChange]] ]) to be persisted
   * @param linkDataRetrievalTime
   * @param linkGeomSource Source from where the link data / geometries of the set have been
   * @param changeSetName of type [[LinkNetworkChange]]
   * @throws ViiteException if any of the change data is invalid, or incongruent, or there is no such change to be made within Viite
   */
  def persistLinkNetworkChanges(changeSet: Seq[LinkNetworkChange],
                                changeSetName: String,
                                linkDataRetrievalTime: DateTime,
                                linkGeomSource: LinkGeomSource = LinkGeomSource.NormalLinkInterface
                               ): Unit = {
    val changeMetaData = ChangeSetMetaData(changeSetName, linkDataRetrievalTime, linkGeomSource)
    persistLinkNetworkChanges(changeSet, changeMetaData)
  }

  /**
   * Takes in a change set describing dynamic changes to the underlying link network,
   * validates each change, and makes the corresponding changes to the Viite data structures,
   * iff all changes are valid changes for Viite.
   * @note  All of the changes are run within in a single transaction, that either passes or fails.
   *        That is, either the whole changeSet is set as the Viite link network state, or none of it.
   *
   * @param changeSet List of link network changes (Seq[ [[LinkNetworkChange]] ]) to be persisted
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

    LogUtils.time(logger, s"Persist LinkNetworkChanges to Viite data structures, change set ${changeMetaData.changeSetName}") {
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

    logger.debug("Going to transformed to a LinkNetworkReplaceChange")
    val aReplaceChange: LinkNetworkReplaceChange = convertToAValidReplaceChange(change).get // returns or throws
    logger.debug("Transformed to a LinkNetworkReplaceChange")

    LogUtils.time(logger, s"Make a Replace change to Viite data structures (${change.oldLink.linkId}=>${change.newLinks.map(nl => nl.linkId).mkString(", ")}") {
      linkChangesDueToNetworkLinkChange(change, changeMetaData);                      logger.debug("Link created")
      calibrationPointChangesDueToNetworkLinkReplace(aReplaceChange, changeMetaData); logger.debug("CalibrationPoints changed")
      linearLocationChangesDueToNetworkLinkReplace(aReplaceChange, changeMetaData);   logger.debug("Linear locations changed")
    }
  }

  private def persistSplitChange(change: LinkNetworkChange, changeMetaData: ChangeSetMetaData): Unit = {

    logger.debug("Going to transformed to a LinkNetworkSplitChange")
    val aSplitChange: LinkNetworkSplitChange = convertToAValidSplitChange(change).get // returns or throws
    logger.debug("Transformed to a LinkNetworkSplitChange")

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
   * @throws [[ViiteException]] when the given <i>change</i> (of changeType "split") is not a valid
   *                        LinkNetworkSplitChange, but it has structural, or logical flaws.
   */
  private def convertToAValidSplitChange(change: LinkNetworkChange): Option[LinkNetworkSplitChange] = {
    change.changeType match {
      case "split" =>

        // Validate the change for a replace change; get out with a throw if the change is not a valid replace change

        // -- size considerations --
        if ( change.newLinks.size <= 2
          || change.replaceInfo.size <= 2
          || change.newLinks.size != change.replaceInfo.size
        ) {
          throw ViiteException(s"LinkNetworkChange: Invalid SplitChange. A split must have at least " +
            s"two new links, and their corresponding replace infos. " +
            s"There are ${change.newLinks.size} new links, and ${change.replaceInfo.size} replace infos " +
            s"when going to split ${change.oldLink.linkId}.")
        }
        // Ok, now we know we have a proper amount of components
        val oldLink = change.oldLink
        val newLinks = change.newLinks
        val splitInfos = change.replaceInfo

        // -- data integrity - link ids --
        if (!splitInfos.forall(si => oldLink.linkId == si.oldLinkId)) {
          throw ViiteException(s"LinkNetworkChange: Invalid SplitChange. The old link ${oldLink.linkId} " +
            s"must be part of all of the replace infos. ")
        }
        newLinks.foreach(nl =>
          if(!splitInfos.exists(si => nl.linkId==si.newLinkId)) {
            throw ViiteException(s"LinkNetworkChange: Invalid SplitChange. Correspondence between " +
              s"the new link ${nl.linkId}, and a replace info links is not found. ")
          }
        )

        // -- data integrity - lengths --
        val oldLengthFromSplitInfos = splitInfos.foldLeft(0.0)((s1,s2) => s1 + s2.oldToMValue-s2.oldFromMValue)
        val oldLengthFromOldLink    = oldLink.linkLength
        val newLengthFromSplitInfos = splitInfos.foldLeft(0.0)((s1,s2) => s1 + s2.newToMValue-s2.newFromMValue)
        val newLengthFromNewLinks   = newLinks.foldLeft(0.0)((s1,s2) => s1 + s2.linkLength)

        if (oldLengthFromOldLink != oldLengthFromSplitInfos) {  // old link lengths must always match
          throw ViiteException(s"LinkNetworkChange: Invalid SplitChange. Old link lengths do not match when splitting link $oldLink." +
            s"Check that lengths (oldToMValue-oldFromMValue) in the replaceInfos (now $oldLengthFromSplitInfos) " +
            s"sum up to that of the old link length ($oldLink.linkLength)")
        }
        if (newLengthFromNewLinks != newLengthFromSplitInfos) { // new link lengths must always match
          throw ViiteException(s"LinkNetworkChange: Invalid SplitChange. New link lengths do not match when splitting link $oldLink." +
            s"Check that lengths (newToMValue-newFromMValue) in the replaceInfos (now $newLengthFromSplitInfos) " +
            s"sum up to that of the lengths of the new links ($newLengthFromNewLinks).")
        }

        // -- data integrity - geometry --
        if(oldLink.geometry.size<2) {
          throw ViiteException(s"LinkNetworkChange: Invalid old link geometry. " +
            s"A geometry must have at least two points ${oldLink.linkId}:${oldLink.geometry}")
        }
        newLinks.foreach(
          nl =>  if(nl.geometry.size<2) {
            throw ViiteException(s"LinkNetworkChange: Invalid new link geometry. " +
              s"A geometry must have at least two points ${nl.linkId}:${nl.geometry}")
          }
        )
        //TODO link.geometry vs. link.linkLength checks?

        splitInfos.foreach(ri =>
          if (ri.digitizationChange) {
            // TODO is digitizationChange always ok as is, or do we have to check something when it is true?
          }
        )

        // Validations passed; return a proper LinkNetworkSplitChange.
        Some(LinkNetworkSplitChange(oldLink, newLinks, splitInfos))

      case _ => // This does not even claim to be a split typed change
        None
    } // match
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
    change.changeType match {
      case "replace" =>
        // Validate the change for a replace change; get out with a throw if the change is not a valid replace change

        logger.debug("size considerations")
        // -- size considerations --
        if (change.newLinks.isEmpty || change.replaceInfo.isEmpty) {
          throw ViiteException(s"LinkNetworkChange: Invalid ReplaceChange. A replace must have a new link, and a replace info. " +
            s"occurred when going to replace ${change.oldLink.linkId}.")
        }
        if (change.newLinks.size != 1) {
          throw ViiteException(s"LinkNetworkChange: Invalid ReplaceChange. A replace must have exactly one new link. " +
            s"There are ${change.newLinks.size} new links when going to replace ${change.oldLink.linkId}.")
        }
        if (change.replaceInfo.size != 1) {
          throw ViiteException(s"LinkNetworkChange: Invalid ReplaceChange. A replace must have exactly one replaceInfo. " +
            s"There are ${change.replaceInfo.size} replace infos when going to replace ${change.oldLink.linkId}.")
        }
        // Ok, we know we have a proper amount of components
        val oldLink = change.oldLink
        val newLink = change.newLinks.head
        val replaceInfo = change.replaceInfo.head

        logger.debug("data integrity: ids")
        // -- data integrity --
        if (oldLink.linkId != replaceInfo.oldLinkId || newLink.linkId != replaceInfo.newLinkId) {
          throw ViiteException(s"LinkNetworkChange: Invalid ReplaceChange. A link ids do not match. Check " +
            s"${oldLink.linkId} vs. ${replaceInfo.oldLinkId}, and ${newLink.linkId} vs. ${replaceInfo.newLinkId}.")
        }
        logger.debug("data integrity: lengths")
        if (oldLink.linkLength != replaceInfo.oldToMValue // old link lengths must always match
          ||newLink.linkLength > replaceInfo.newToMValue // new link may be a combination of two old -> replaceInfo may have partial measure here
        ) {
          throw ViiteException(s"LinkNetworkChange: Invalid ReplaceChange. A link lengths do not match. Check " +
            s"${oldLink.linkLength} vs. ${replaceInfo.oldToMValue}, and ${newLink.linkLength} vs. ${replaceInfo.newToMValue}.")
        }
        logger.debug("data integrity: digitization")
        if (replaceInfo.digitizationChange) {
          // TODO is digitizationChange always ok as is, or do we have to check something when it is true?
        }

        logger.debug("validations passed")
        // Validations passed; return a proper LinkNetworkReplaceChange.
        Some(LinkNetworkReplaceChange(oldLink, newLink, replaceInfo))

      case _ => // This does not even claim to be a replace typed change
        None
    } // match
  }

  /**
   * Invalidates the old, and created new linear locations according to the given split <i>change</i>.
   *
   * @param change        The link network change whose related changes we want to make to the database.
   * @param changeMetaData changeSetName used as the creator (createdBy) for the linearLocations created.
   * @return              Sequence of ids of the created linearLocations
   */
  private def linearLocationChangesDueToNetworkLinkSplit(change: LinkNetworkSplitChange, changeMetaData: ChangeSetMetaData): Unit = {
    val oldLinearLocations = linearLocationDAO.fetchByLinkId(Set(change.oldLink.linkId))

    oldLinearLocations.size match {
      case 0 =>
        throw ViiteException(s"LinkNetworkChange: No old linear location found " +
          s"for link ${change.oldLink.linkId} when trying to expire old ones.")
      case 1 =>
        //TODO the real stuff is run for this option. No Throws here.
      case _ =>
        //TODO this may be a valid option, too. Should split old, and new to more than one, then?
        throw ViiteException(s"LinkNetworkChange: Too many valid linear locations found " +
          s"for link ${change.oldLink.linkId} when trying to expire old ones.")
    } // match

    val llIds = oldLinearLocations.map(_.id).toSet
    /*val numInvalidatedLLs: Int =*/ linearLocationDAO.expireByIds(llIds)

    // when only one linearlocation, we know what to do
    val oldLL = oldLinearLocations.head
    // TODO what if we have at least two linear locations, what do we do then?

    change.replaceInfo.foreach(ri => {
      val correspondingLinkCandidates: Seq[LinkInfo] = change.newLinks.filter(nl => nl.linkId==ri.newLinkId)
      val correspondingLink = correspondingLinkCandidates.headOption.getOrElse(
        throw ViiteException(s"LinkNetworkChange: Invalid SplitChange. No corresponding link found. ")
      )

      val newLL = LinearLocation(
        NewIdValue,          //id: Long,
        oldLL.orderNumber,   //orderNumber: Double,
        ri.newLinkId,        //linkId: String,
        ri.newFromMValue,    //startMValue: Double,
        ri.newToMValue,      //endMValue:   Double,
        decideNewSideCode(ri.digitizationChange, oldLL.sideCode),
        0, // Not required, link created elsewhere //adjustedTimestamp: Long,
        (CalibrationPointReference.None, CalibrationPointReference.None), //CPs created elsewhere //calibrationPoints: (CalibrationPointReference, CalibrationPointReference) = (CalibrationPointReference.None, CalibrationPointReference.None)
        Seq(correspondingLink.geometry.head, correspondingLink.geometry.last), //geometry: Seq[Point]
        LinkGeomSource.Unknown, // Not required, link created elsewhere
        oldLL.roadwayNumber,    //roadwayNumber: Long
        None, // Not used at create         //validFrom: Option[DateTime] = None
        None  // Not used at create         //validTo:   Option[DateTime] = None
      ) // LinearLocation

    linearLocationDAO.create(Seq(newLL), changeMetaData.changeSetName)
    }) // change.replaceInfo.foreach
  }

  private def calibrationPointChangesDueToNetworkLinkSplit(change: LinkNetworkSplitChange, changeMetaData: ChangeSetMetaData): Seq[(Long, Long)] = { // TODO RETURN id mapper list for old-new CPs?
    val oldLinkId = change.oldLink.linkId
    val CPsOfOldLink: Seq[CalibrationPoint] = CalibrationPointDAO.fetchByLinkId(Seq(oldLinkId))
    val startCP: Option[CalibrationPoint] = CPsOfOldLink.find(cp => cp.startOrEnd==StartOfLink)
    val endCP:   Option[CalibrationPoint] = CPsOfOldLink.find(cp => cp.startOrEnd==EndOfLink  )

    //if (CPsOfOldLink == None) { // There might be none, and that is ok.
    //  throw ViiteException(s"LinkNetworkChange: No calibration points for old link $oldLinkId found on road network")
    //}

    // expire old calibration points, referring to the old link
    CalibrationPointDAO.expireById(CPsOfOldLink.map(_.id))

    //TODO RETURN id mapper list for old-new CPs
    var idPairs: Seq[(Long, Long)] = Seq()

    // create corresponding calibration points for new links, based on the old calibration points
    val linkStartInfo: Option[ReplaceInfo] = change.replaceInfo.find(ri => ri.oldFromMValue == 0)
    val linkEndInfo:   Option[ReplaceInfo] = change.replaceInfo.find(ri => ri.oldToMValue == change.oldLink.linkLength)
    //val swapEnd: Boolean = change.replaceInfo.head.digitizationChange
    //val newStartEnd = oldCP.startOrEnd
    //val newStartEnd = swapEnd match { // Take the possible link direction change into account
    //  case false => oldCP.startOrEnd
    //  case true => if (oldCP.startOrEnd == StartOfLink) EndOfLink else StartOfLink
    //}
    //val newCP = oldCP.copy(id = NewIdValue, linkId = newLinkId, startOrEnd = newStartEnd)
    val newStartCP = startCP.get.copy(
      id = NewIdValue,
      linkId = linkStartInfo.get.newLinkId,
      createdBy = changeMetaData.changeSetName,
      createdTime = Some(changeMetaData.linkDataRetrievalDate)
    )
    val newStartCPid = CalibrationPointDAO.create(Seq(newStartCP)).head // we know we have only one
    idPairs = idPairs :+ (newStartCP.id, newStartCPid)

    val newEndCP = endCP.get.copy(id = NewIdValue, linkId = linkEndInfo.get.newLinkId)
    val newEndCPid = CalibrationPointDAO.create(Seq(newEndCP)).head // we know we have only one
    idPairs = idPairs :+ (newEndCP.id, newEndCPid)

    //TODO RETURN id mapper list for old-new CPs
    idPairs
  }

  /**
   * Invalidates the old, and created new linear locations according to the given replace <i>change</i>.
   *
   * @param change        The link network change whose related changes we want to make to the database.
   * @param changeSetName Used as the creator (createdBy) for the linearLocations created.
   * @return              Sequence of ids of the created linearLocations
   */
  private def linearLocationChangesDueToNetworkLinkReplace(change: LinkNetworkReplaceChange, changeMetaData: ChangeSetMetaData) = {
    val replInfo = change.replaceInfo
    val oldLinearLocations = linearLocationDAO.fetchByLinkIdAndMValueRange(change.oldLink.linkId, replInfo.oldFromMValue, replInfo.newToMValue)

    oldLinearLocations.size match {
      case 0 =>
        throw ViiteException(s"LinkNetworkChange: No old linear location found " +
          s"for link ${change.oldLink.linkId} when trying to expire old ones.")
      case _ =>
        //TODO the real stuff is run for this option. No Throws here.
    }

    val llIds = oldLinearLocations.map(_.id).toSet

    oldLinearLocations.foreach(oldLL => {
      val newLL = LinearLocation(
        NewIdValue,                                //id:          Long,
        oldLL.orderNumber,                         //orderNumber: Double,
        change.newLink.linkId,                     //linkId: String,
        change.replaceInfo.newFromMValue,          //startMValue: Double, // TODO change .newFromMValue to an interpolating algorithm, when geometry changed
        change.replaceInfo.newToMValue,            //endMValue:   Double, // TODO change .newFromMValue to an interpolating algorithm, when geometry changed
        decideNewSideCode(change.replaceInfo.digitizationChange, oldLL.sideCode),
        0, // Not required, link created elsewhere //adjustedTimestamp: Long,
        (CalibrationPointReference.None, CalibrationPointReference.None), //CPs created elsewhere //calibrationPoints: (CalibrationPointReference, CalibrationPointReference) = (CalibrationPointReference.None, CalibrationPointReference.None)
        Seq(change.newLink.geometry.head, change.newLink.geometry.last),  //geometry: Seq[Point]
        LinkGeomSource.Unknown, // Not required, link created elsewhere
        oldLL.roadwayNumber,                       //roadwayNumber: Long
        None,        // Not used at create (why?)  //validFrom: Option[DateTime] = None
        None         // Not used at create (why?)  //validTo:   Option[DateTime] = None
      )

      linearLocationDAO.create(Seq(newLL), changeMetaData.changeSetName)
      /*val numInvalidatedLLs: Int =*/ linearLocationDAO.expireByIds(llIds)
    }) // oldLinearLocations.foreach
  }

  private def calibrationPointChangesDueToNetworkLinkReplace(change: LinkNetworkReplaceChange, changeMetaData: ChangeSetMetaData): Seq[(Long, Long)] = {    //TODO RETURN id mapper list for old-new CPs
    val oldLinkId = change.oldLink.linkId
    val CPsOfOldLink = CalibrationPointDAO.fetchByLinkId(Seq(oldLinkId))

    //if (CPsOfOldLink == None) { // There might be none, and that is ok.
    //  throw ViiteException(s"LinkNetworkChange: No calibration points for old link $oldLinkId found on road network")
    //}

    // -- obsolete with LinkNetworkReplaceChange type --
    //if (change.replaceInfo.size > 1) {
    //  throw ViiteException(s"LinkNetworkChange: Replace cannot handle more than one replaceInfo. " +
    //    s"There are ${change.replaceInfo.size} when replacing $oldLinkId.")
    //}
    //if (change.newLinks.size>1) {
    // throw ViiteException(s"LinkNetworkChange: Replace cannot handle more than one new link. " +
    //                      s"There are ${change.newLinks.size} when replacing $oldLinkId.")
    //}
    //val newLinkId = change.newLinks.head.linkId
    val newLinkId = change.newLink.linkId

    // expire old calibration points, referring to the old link
    CalibrationPointDAO.expireById(CPsOfOldLink.map(_.id))

    //TODO RETURN id mapper list for old-new CPs
    var idPairs: Seq[(Long, Long)] = Seq()

    // create corresponding calibration points, based on the old calibration points
    CPsOfOldLink.foreach(oldCP => {
      //val swapEnd: Boolean = change.replaceInfo.head.digitizationChange
      //val newStartEnd = oldCP.startOrEnd
      //val newStartEnd = swapEnd match { // Take the possible link direction change into account
      //  case false => oldCP.startOrEnd
      //  case true => if (oldCP.startOrEnd == StartOfLink) EndOfLink else StartOfLink
      //}
      //val newCP = oldCP.copy(id = NewIdValue, linkId = newLinkId, startOrEnd = newStartEnd)
      val newCP = oldCP.copy(
        id = NewIdValue,
        linkId = newLinkId,
        createdBy = changeMetaData.changeSetName,
        createdTime = Some(changeMetaData.linkDataRetrievalDate)
      )
      val newCPid = CalibrationPointDAO.create(Seq(newCP)).head // we know we have only one
      idPairs = idPairs:+(oldCP.id,newCPid)
    })

    //TODO RETURN id mapper list for old-new CPs
    idPairs
  }

  /** Creates new links according to the given link network change object.
   * A duplicate link is, however, not created.
   *
   * @param change A single (simplest possible) change object from the set of link network changes
//   * @param changeSetName Name of the change set where the change has been introduced. // TODO Or should get the original geometry source, instead?
   * @param geomSource ENumerated data source from where the link information has been retrieved.
   * @param linkDataRetrievalDate The timestamp to be saved as the link.adjustedTimestamp; the time when the link information
   *                              has been retrieved from the link source.
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
        changeMetaData.linkGeomSource.value  // TODO Would prefer name of the change set, but oh well.
      )
     }
    )
    logger.debug("New links created")
  }

  private def decideNewSideCode(digitizationChanged: Boolean, oldSideCode: SideCode) = {
    if (digitizationChanged) SideCode.switch(oldSideCode) else oldSideCode
  }
}