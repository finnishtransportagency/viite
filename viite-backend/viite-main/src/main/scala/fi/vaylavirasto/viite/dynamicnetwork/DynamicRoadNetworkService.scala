package fi.vaylavirasto.viite.dynamicnetwork

import fi.liikennevirasto.digiroad2.client.kgv.KgvRoadLink
import fi.liikennevirasto.digiroad2.client.vkm.{TiekamuRoadLinkChange, TiekamuRoadLinkChangeError, TiekamuRoadLinkErrorMetaData, VKMClient}
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.ViiteProperties
import fi.liikennevirasto.viite.AwsService
import fi.liikennevirasto.viite.dao.{LinearLocation, LinearLocationDAO, Roadway, RoadwayDAO}
import fi.vaylavirasto.viite.dao.ComplementaryLinkDAO
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point}
import fi.vaylavirasto.viite.geometry.GeometryUtils.scaleToThreeDigits
import fi.vaylavirasto.viite.model.{LinkGeomSource, RoadLink, RoadPart}
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC
import fi.vaylavirasto.viite.util.ViiteException
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.joda.time.DateTime
import org.json4s.DefaultFormats
import org.json4s.jackson.Json
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable.ListBuffer

class DynamicRoadNetworkService(linearLocationDAO: LinearLocationDAO, roadwayDAO: RoadwayDAO, val kgvClient: KgvRoadLink, awsService: AwsService, linkNetworkUpdater: LinkNetworkUpdater) {

  val bucketName: String = ViiteProperties.dynamicLinkNetworkS3BucketName
  val vkmClient = new VKMClient(ViiteProperties.vkmUrlDev, ViiteProperties.vkmApiKeyDev)

  def runWithTransaction[T](f: => T): T = PostGISDatabaseScalikeJDBC.runWithTransaction(f)
  
  implicit val formats = DefaultFormats
  val logger: Logger = LoggerFactory.getLogger(getClass)

  val complementaryLinkDAO: ComplementaryLinkDAO = new ComplementaryLinkDAO

  // LOCAL DEBUG: set the `localS3DebugDir` property (env var or env.properties) to write all dynamic-network
  // S3 files to that local directory instead of the real S3 bucket. Leave unset for normal S3 behaviour.
  private val localS3DebugDir: String = ViiteProperties.localS3DebugDir

  private def saveToS3(bucket: String, id: String, body: String, responseType: String): Unit = {
    if (localS3DebugDir == null || localS3DebugDir.isEmpty) {
      awsService.S3.saveFileToS3(bucket, id, body, responseType)
    } else {
      val dir = java.nio.file.Paths.get(s"$localS3DebugDir/$bucket")
      java.nio.file.Files.createDirectories(dir)
      java.nio.file.Files.write(dir.resolve(id.replace('/', '_')), body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      logger.info(s"[LOCAL DEBUG] S3 write → $dir/${id.replace('/', '_')}")
    }
  }

  def tiekamuRoadLinkChangeErrorToMap(tiekamuRoadLinkChangeError: TiekamuRoadLinkChangeError): Map[String, Any] = {
    Map(
      "errorMessage" -> tiekamuRoadLinkChangeError.errorMessage,
      "oldLinkId" -> tiekamuRoadLinkChangeError.change.oldLinkId,
      "oldStartM" -> tiekamuRoadLinkChangeError.change.oldStartM,
      "oldEndM" -> tiekamuRoadLinkChangeError.change.oldEndM,
      "newLinkId" -> tiekamuRoadLinkChangeError.change.newLinkId,
      "newStartM" -> tiekamuRoadLinkChangeError.change.newStartM,
      "newEndM" -> tiekamuRoadLinkChangeError.change.newEndM,
      "digitizationChange" -> tiekamuRoadLinkChangeError.change.digitizationChange,
      "roadNumber" -> tiekamuRoadLinkChangeError.metaData.roadPart.roadNumber,
      "roadPartNumber" -> tiekamuRoadLinkChangeError.metaData.roadPart.partNumber,
      "linearLocationIds" -> tiekamuRoadLinkChangeError.metaData.linearLocationIds
    )
  }

  def skippedTiekamuRoadLinkChangeToMap(change: TiekamuRoadLinkChange): Map[String, Any] = {
    Map(
      "oldLinkId" -> change.oldLinkId,
      "oldStartM" -> change.oldStartM,
      "oldEndM" -> change.oldEndM,
      "newLinkId" -> change.newLinkId,
      "newStartM" -> change.newStartM,
      "newEndM" -> change.newEndM,
      "digitizationChange" -> change.digitizationChange
    )
  }

  def linkNetworkChangeToMap(change: LinkNetworkChange): Map[String, Any] = {
    Map(
      "changeType" -> change.changeType,
      "oldLink" -> linkInfoToMap(change.oldLink),
      "newLinks" -> change.newLinks.map(changeInfo => linkInfoToMap(changeInfo)),
      "replaceInfos" -> change.replaceInfos.map(replace => replaceInfoToMap(replace))
    )
  }

  def linkInfoToMap(linkInfo: LinkInfo): Map[String, Any] = {
    Map(
      "linkId" -> linkInfo.linkId,
      "linkLength" -> linkInfo.linkLength,
      "geometry" -> linkInfo.geometry
    )
  }

  def replaceInfoToMap(replaceInfo: ReplaceInfo): Map[String, Any] = {
    Map(
      "oldLinkId" -> replaceInfo.oldLinkId,
      "oldFromMValue" -> replaceInfo.oldFromMValue,
      "oldToMValue" -> replaceInfo.oldToMValue,
      "newFromMValue" -> replaceInfo.newFromMValue,
      "newToMValue" -> replaceInfo.newToMValue,
      "digitizationChange" -> replaceInfo.digitizationChange,
      "oldLinkViiteData" -> replaceInfo.oldLinkViiteData.map(oldLinkViiteData => viiteMetaDataToMap(oldLinkViiteData))
    )
  }

  def viiteMetaDataToMap(data: ViiteMetaData): Map[String, Any] = {
    Map(
      "linearLocationId" -> data.linearLocationId,
      "roadwayNumber" -> data.roadwayNumber,
      "orderNumber" -> data.orderNumber,
      "roadNumber" -> data.roadPart.roadNumber,
      "roadPartNumber" -> data.roadPart.partNumber
    )
  }

  def createViiteLinkNetworkChanges(tiekamuRoadLinkChanges: Seq[TiekamuRoadLinkChange],
                                    activeLinearLocations: Seq[LinearLocation],
                                    kgvRoadLinks: Seq[DynamicRoadNetworkService.this.kgvClient.roadLinkVersionsData.LinkType],
                                    complementaryLinks: Seq[RoadLink]): Seq[LinkNetworkChange] = {
    def createOldLinkInfo(kgvRoadLinks: Seq[DynamicRoadNetworkService.this.kgvClient.roadLinkVersionsData.LinkType], complementaryLinks: Seq[RoadLink], oldLinkId: String): LinkInfo = {
      val oldLinkInfo = {
        val oldLink = (kgvRoadLinks ++ complementaryLinks).find(rl => rl.linkId == oldLinkId)
        if (oldLink.isDefined)
          LinkInfo(oldLinkId, scaleToThreeDigits(oldLink.get.length), oldLink.get.geometry)
        else
          throw ViiteException(s"Can't create change set without KGV/complementary road link data for oldLinkId: ${oldLinkId} ")
      }
      oldLinkInfo
    }

    def createNewLinkInfos(kgvRoadLinks: Seq[DynamicRoadNetworkService.this.kgvClient.roadLinkVersionsData.LinkType], complementaryLinks: Seq[RoadLink], distinctChangeInfosByNewLink: Seq[TiekamuRoadLinkChange]): Seq[LinkInfo] = {
      val newLinkInfo = distinctChangeInfosByNewLink.map(ch => {
        val newLink = (kgvRoadLinks ++ complementaryLinks).find(newLink => ch.newLinkId == newLink.linkId)
        if (newLink.isDefined)
          LinkInfo(ch.newLinkId, scaleToThreeDigits(newLink.get.length), newLink.get.geometry)
        else
          throw ViiteException(s"Can't create change set without KGV/complementary road link data for newLinkId: ${ch.newLinkId} ")
      })
      newLinkInfo
    }

    def createReplaceInfos(tiekamuRoadLinkChanges: Seq[TiekamuRoadLinkChange]): Seq[ReplaceInfo] = {
      def createViiteMetaData(linearLocations: Seq[LinearLocation]): Seq[ViiteMetaData] = {
        val viiteMetaData = linearLocations.map(ll => {
          val roadway = roadwayDAO.fetchAllByRoadwayNumbers(Set(ll.roadwayNumber)).head
          ViiteMetaData(ll.id, ll.startMValue, ll.endMValue, ll.roadwayNumber, ll.orderNumber.toInt, roadway.roadPart)
        })
        viiteMetaData
      }

      /**
       * In order to merge TiekamuRoadLinkChanges to one they need to:
       * - share the same oldLinkId
       * - share the same newLinkId
       * - be continuous by the M values
       *
       * So first we group the TiekamuRoadLinkChanges by the old- and newLinkId.
       * Then we order the groups by the oldStartM -value
       * Then we create continuous sections of those groups
       * Then those sections can be merged in to one TiekamuRoadLinkChange
       * And lastly we return the list of merged TiekamuRoadLinkChanges
       */
      def mergeTiekamuRoadLinkChanges(tiekamuRoadLinkChanges: Seq[TiekamuRoadLinkChange]): Seq[TiekamuRoadLinkChange] = {
        def createSections(tiekamuRoadLinkChanges: Seq[TiekamuRoadLinkChange]): Seq[Seq[TiekamuRoadLinkChange]] = {
          tiekamuRoadLinkChanges.foldLeft(Seq[Seq[TiekamuRoadLinkChange]]()) {
            (sections, change) =>
              sections match {
                // If sections is empty, create a new section with the current change and wrap it in a Seq
                case Seq() => Seq(Seq(change))
                // If there are existing sections, check if the current change can be appended to the last section
                case currentSection +: rest =>
                  if (change.oldStartM == currentSection.last.oldEndM && change.digitizationChange == change.digitizationChange) {
                    (currentSection :+ change) +: rest // Add the updated section to the sections list
                  } else {
                    Seq(change) +: sections // Create a new section with the current change and prepend it to sections
                  }
              }
          }
        }

        def mergeSectionIntoOneTiekamuRoadLinkChange(section: Seq[TiekamuRoadLinkChange]): TiekamuRoadLinkChange = {
          val oldLinkId = section.head.oldLinkId
          val lowestOldStartM = section.map(_.oldStartM).min
          val highestOldEndM = section.map(_.oldEndM).max
          val newLinkId = section.head.newLinkId
          val lowestNewStartM = section.map(_.newStartM).min
          val highestNewEndM = section.map(_.newEndM).max
          val digitizationChange = section.map(_.digitizationChange).distinct

          if (digitizationChange.length > 1)
            throw ViiteException("Too many 'digitizationChange' values in one section for it be merged into one! Section: " + section.foreach(change => change))
          else {
            // Create a merged TiekamuRoadLinkChange with the lowest oldStartM and highest oldEndM
            TiekamuRoadLinkChange(oldLinkId, lowestOldStartM, highestOldEndM, newLinkId, lowestNewStartM, highestNewEndM, digitizationChange.head)
          }
        }

        // Create sections and merge them into a single TiekamuRoadLinkChange
        def createMergedTiekamuRoadLinkChanges(changes: Seq[TiekamuRoadLinkChange]): Seq[TiekamuRoadLinkChange] = {
          // Create individual sections
          val sections: Seq[Seq[TiekamuRoadLinkChange]] = createSections(changes)
          // Merge sections into a single change and collect them into a sequence
          val mergedSections: Seq[TiekamuRoadLinkChange] = sections.map(section => mergeSectionIntoOneTiekamuRoadLinkChange(section))
          mergedSections
        }

        // group the changeInfos by both the oldLinkId and the newLinkId  Map[(oldLinkId, newLinkId), Seq[TiekamuRoadLinkChange]]
        val groupedByBothLinkIds = tiekamuRoadLinkChanges.groupBy(ch => (ch.oldLinkId, ch.newLinkId))

        val mergedTiekamuRoadLinkChanges: Seq[TiekamuRoadLinkChange] = groupedByBothLinkIds.values.flatMap(changes => {
          // sort the changes by oldStartM
          val sortedChanges = changes.sortWith(_.oldStartM < _.oldStartM)
          createMergedTiekamuRoadLinkChanges(sortedChanges)
        }).toSeq

        mergedTiekamuRoadLinkChanges
      }

      val mergedTiekamuRoadLinkChanges = mergeTiekamuRoadLinkChanges(tiekamuRoadLinkChanges)

      val replaceInfos = mergedTiekamuRoadLinkChanges.map(ch => {
        val linearLocations = activeLinearLocations.filter(ll => ll.linkId == ch.oldLinkId)
        if (linearLocations.nonEmpty) {
          val viiteMetaData = createViiteMetaData(linearLocations)
          ReplaceInfo(ch.oldLinkId, ch.oldStartM, ch.oldEndM, ch.newLinkId, ch.newStartM, ch.newEndM, ch.digitizationChange, viiteMetaData)
        } else
          throw ViiteException(s"Can't create change set without existing active Linearlocation with old linkId ${ch.oldLinkId} and startMValue ${ch.oldStartM} and endMValue ${ch.oldEndM}")
      })
      replaceInfos
    }

    time(logger, "Creating Viite LinkNetworkChange sets") {
      val groupedByOldLinkId = tiekamuRoadLinkChanges.groupBy(changeInfo => changeInfo.oldLinkId)

      val linkNetworkChanges = groupedByOldLinkId.map(group => {
        val oldLinkId = group._1
        val tiekamuRoadLinkChangeInfos = group._2
        val distinctChangeInfosByNewLink = tiekamuRoadLinkChangeInfos.groupBy(_.newLinkId).values.map(_.head).toSeq

        val viiteRoadLinkChange = {
          val changeType = if (tiekamuRoadLinkChangeInfos.map(_.newLinkId).distinct.size > 1) "split" else "replace"
          val oldInfo = createOldLinkInfo(kgvRoadLinks, complementaryLinks, oldLinkId)
          val newInfo = createNewLinkInfos(kgvRoadLinks, complementaryLinks, distinctChangeInfosByNewLink)
          val replaceInfo = createReplaceInfos(tiekamuRoadLinkChangeInfos)
          LinkNetworkChange(changeType, oldInfo, newInfo, replaceInfo)
        }
        viiteRoadLinkChange
      }).toSeq
      linkNetworkChanges
    }
  }

  /**
   * Tiekamu can describe a single old→new link mapping with several OVERLAPPING M-range rows
   * (e.g. a version bump "X:1 → X:2" arriving as 0..49, 0..16, 16..49, 16..38). These overlaps are
   * not a real merge or a partial change — they redundantly re-cover the same span. Left untouched
   * they (1) inflate the summed length in validateTiekamuRoadLinkChanges, yielding a false
   * "No partial changes allowed" error, (2) look like several changes into one new link and so
   * trigger a false combination / "not continuous" error, and (3) would create overlapping
   * ReplaceInfos downstream.
   *
   * For each (oldLinkId, newLinkId, digitizationChange) group we cluster rows whose old-link M-ranges
   * overlap or touch, and replace each cluster with a single change spanning the union on both the
   * old and the new side. Genuinely disjoint ranges (a real partial change with a gap) remain
   * separate clusters, so genuine partial-coverage cases still fail validation exactly as before.
   */
  def collapseOverlappingChanges(changes: Seq[TiekamuRoadLinkChange]): Seq[TiekamuRoadLinkChange] = {
    // The old-link M-range may be stored descending (oldStartM > oldEndM), so normalise to [lo, hi]
    // for overlap detection and restore the group's orientation on output.
    def oldLo(ch: TiekamuRoadLinkChange): Double = math.min(ch.oldStartM, ch.oldEndM)
    def oldHi(ch: TiekamuRoadLinkChange): Double = math.max(ch.oldStartM, ch.oldEndM)

    // One-sided rows (no old link, or no new link) describe new or removed geometry rather than a
    // mapping, so there is nothing to collapse for them; they pass through untouched.
    val (oneSided, mappings) = changes.partition(ch => ch.oldLinkId == null || ch.newLinkId == null)
    oneSided ++ mappings.groupBy(ch => (ch.oldLinkId, ch.newLinkId, ch.digitizationChange)).values.flatMap { group =>
      val ascending = group.head.oldEndM >= group.head.oldStartM
      // Cluster rows whose normalised old-M ranges overlap or touch (within DefaultEpsilon).
      val clusters = group.sortBy(oldLo).foldLeft(List.empty[List[TiekamuRoadLinkChange]]) {
        case (head :: tail, ch) if oldLo(ch) <= head.map(oldHi).max + GeometryUtils.DefaultEpsilon =>
          (ch :: head) :: tail
        case (acc, ch) =>
          List(ch) :: acc
      }
      clusters.map { cluster =>
        val first = cluster.head
        val lo = cluster.map(oldLo).min
        val hi = cluster.map(oldHi).max
        val (oldStart, oldEnd) = if (ascending) (lo, hi) else (hi, lo)
        TiekamuRoadLinkChange(
          first.oldLinkId, oldStart, oldEnd,
          first.newLinkId, cluster.map(_.newStartM).min, cluster.map(_.newEndM).max,
          first.digitizationChange
        )
      }
    }.toSeq
  }

  /**
   * Drops the rows that describe no change, removes exact duplicates, and reconciles rows that
   * redundantly re-cover the same old→new mapping.
   *
   * A "no-op" row maps a link onto itself with identical M-values and no digitization change, i.e. the
   * link did not change. That is not an error, so it must not reach validateTiekamuRoadLinkChanges
   * (which would flag it as "No changes found in the changeset" and drop the whole road part), nor
   * produce a redundant self-replace in the change set.
   *
   * Run once on the fetched change set, and again after link ids have been resolved against KGV:
   * resolving "<uuid>:a" to "<uuid>:1" can turn a row into a no-op that was not one when fetched.
   *
   * @param label names the pass in the log
   */
  def normaliseChangeSet(changes: Seq[TiekamuRoadLinkChange], label: String): Seq[TiekamuRoadLinkChange] = {
    val (noOpChanges, effectiveChanges) = changes.partition(ch =>
      ch.oldLinkId == ch.newLinkId &&
        ch.oldStartM == ch.newStartM &&
        ch.oldEndM == ch.newEndM &&
        !ch.digitizationChange
    )
    if (noOpChanges.nonEmpty)
      logger.info(s"Filtered out ${noOpChanges.length} no-op TiekamuRoadLinkChange(s) ($label; link unchanged); ${effectiveChanges.length} remain.")

    val distinctChanges = effectiveChanges.distinct
    val collapsedChanges = collapseOverlappingChanges(distinctChanges)
    val collapsedAway = distinctChanges.length - collapsedChanges.length
    if (collapsedAway > 0)
      logger.info(s"Collapsed $collapsedAway overlapping TiekamuRoadLinkChange row(s) into spanning changes ($label); ${collapsedChanges.length} remain.")

    collapsedChanges
  }

  /**
   * The Tiekamu change rows for the given period.
   *
   * @return (changes on road addressed old links, all changes). Only the first set is applied to the
   *         road network, but validation needs the full set. The rest of a new link can come from an
   *         unaddressed old link, from brand new geometry (a row with no old link), and the missing part
   *         of an old link can be geometry that was removed (a row with no new link). Validating against
   *         the filtered set alone makes all three look like a forbidden partial change, and makes a
   *         changed-but-unaddressed neighbour look changeless.
   */
  def createTiekamuRoadLinkChangeSets(previousDate: DateTime, newDate: DateTime, activeLinearLocations: Seq[LinearLocation]): (Seq[TiekamuRoadLinkChange], Seq[TiekamuRoadLinkChange]) = {

    /**
     * Viite is only interested in change infos that affect links that have road addressed roads on them.
     * Therefore we filter out all the unnecessary change infos i.e. unaddressed link change infos.
     *
     * One-sided rows (new geometry with no old link, or a removed piece with no new link) are dropped
     * here as well: they cannot be applied to the road network. They stay in the full change set,
     * which is what validation reads.
     */
    def getChangeInfosWithRoadAddress(tiekamuRoadLinkChanges: Seq[TiekamuRoadLinkChange], activeLinearLocationsInViite: Seq[LinearLocation]): Seq[TiekamuRoadLinkChange] = {
      val twoSidedChanges = tiekamuRoadLinkChanges.filter(ch => ch.oldLinkId != null && ch.newLinkId != null)
      val oldLinkIds = twoSidedChanges.map(_.oldLinkId).toSet
      val targetLinearLocations = linearLocationDAO.fetchByLinkId(oldLinkIds)
      val filteredLinearLocations = targetLinearLocations.filter(ll => activeLinearLocationsInViite.map(_.id).contains(ll.id))
      val addressedLinkIds = filteredLinearLocations.map(_.linkId).toSet
      val filteredActiveChangeInfos = twoSidedChanges.filter(rlc => addressedLinkIds.contains(rlc.oldLinkId))

      filteredActiveChangeInfos
    }


    time(logger, "Creating Viite road link change info sets") {
      val tiekamuRoadLinkChanges = vkmClient.getTiekamuRoadlinkChanges(previousDate, newDate)
      logger.info(s"${tiekamuRoadLinkChanges.length} TiekamuRoadLinkChanges fetched.")

      // Normalised before the road address filter so that the full set handed to validation is
      // normalised too.
      val collapsedChanges = normaliseChangeSet(tiekamuRoadLinkChanges, "fetched")

      // filter change infos so that only the ones that target links with road addresses are left
      val roadAddressedRoadLinkChanges = getChangeInfosWithRoadAddress(collapsedChanges, activeLinearLocations)
      logger.info(s"${roadAddressedRoadLinkChanges.length} of ${collapsedChanges.length} TiekamuRoadLinkChange(s) are on road addressed links.")

      (roadAddressedRoadLinkChanges, collapsedChanges)
    }
  }

  /**
   * @param tiekamuRoadLinkChanges    The changes to be applied, i.e. changes on road addressed old links.
   * @param allTiekamuRoadLinkChanges The whole change set including changes on unaddressed old links.
   *                                  Used for length/coverage arithmetic and for asking whether a
   *                                  neighbouring link also changed. Defaults to
   *                                  <i>tiekamuRoadLinkChanges</i> when not given.
   */
  def validateTiekamuRoadLinkChanges(tiekamuRoadLinkChanges: Seq[TiekamuRoadLinkChange], linearLocations: Seq[LinearLocation], kgvRoadLinks: Seq[DynamicRoadNetworkService.this.kgvClient.roadLinkVersionsData.LinkType], complementaryLinks: Seq[RoadLink],
                                     allTiekamuRoadLinkChanges: Seq[TiekamuRoadLinkChange] = Seq.empty
                                    ): Seq[TiekamuRoadLinkChangeError] = {

    /** The whole change set: the changes to apply plus the ones on unaddressed old links. */
    val allChanges: Seq[TiekamuRoadLinkChange] =
      if (allTiekamuRoadLinkChanges.isEmpty) tiekamuRoadLinkChanges else allTiekamuRoadLinkChanges

    /**
     * How much two measurements of the same link length may differ before they are considered to
     * describe different extents rather than the same extent measured in a different M-space.
     *
     * Tiekamu M-values (vkm_api_3d m_arvo), KGV horizontallength and the M-values Viite's linear
     * locations carry disagree by up to a few metres on some links. A genuine partial change leaves
     * a considerably larger part of the link untouched, so anything within this deviation is treated
     * as M-space noise.
     */
    val MaxRelativeMSpaceDeviation = 0.05

    val allLinks: Seq[RoadLink] = kgvRoadLinks ++ complementaryLinks
    val linkLengthByLinkId: Map[String, Double] = allLinks.map(link => link.linkId -> link.length).toMap
    val linearLocationsByLinkId: Map[String, Seq[LinearLocation]] = linearLocations.groupBy(_.linkId)
    val addressedLinkIds: Set[String] = linearLocationsByLinkId.keySet

    /**
     * The link's identity without its version token.
     *
     * A link id is "<uuid>:<version>", but Tiekamu does not always use the KGV link version there: the
     * pieces of one physical link can arrive under different tokens (e.g. "<uuid>:1" alongside
     * "<uuid>:b" or "<uuid>:c" — non-numeric tokens that KGV does not know at all). Measuring a new
     * link's coverage per full id splits one link in two, so only part of it looks changed. The
     * M-values of the differently-tokened rows are also sometimes relative to the piece rather than to
     * the link, which is why the coverage of a new link is measured as a total length, not as a span.
     *
     * Used for coverage only. Which links are being merged into one is decided on the exact link id.
     */
    def linkUuid(linkId: String): String = if (linkId == null) null else linkId.takeWhile(_ != ':')

    // Indexed once up front. The change set has hundreds of thousands of rows, so scanning it per
    // change made a run take over an hour.
    val allChangesByOldLinkId: Map[String, Seq[TiekamuRoadLinkChange]] = allChanges.groupBy(_.oldLinkId)
    val allChangesByNewLinkUuid: Map[String, Seq[TiekamuRoadLinkChange]] = allChanges.groupBy(ch => linkUuid(ch.newLinkId))
    val allChangesByNewLinkId: Map[String, Seq[TiekamuRoadLinkChange]] = allChanges.groupBy(_.newLinkId)
    val appliedChangesByNewLinkId: Map[String, Seq[TiekamuRoadLinkChange]] = tiekamuRoadLinkChanges.groupBy(_.newLinkId)

    /** True when two lengths describe the same extent, allowing for M-space deviation. */
    def lengthsMatch(length: Double, otherLength: Double): Boolean = {
      val tolerance = math.max(GeometryUtils.DefaultEpsilon, MaxRelativeMSpaceDeviation * math.max(math.abs(length), math.abs(otherLength)))
      math.abs(length - otherLength) <= tolerance
    }

    /**
     * A Tiekamu M-value remapped into the M-space the link is measured in in Viite.
     *
     * Only applied when the two link lengths are close enough to be the same extent
     * (see [[MaxRelativeMSpaceDeviation]]). When they are far apart the Tiekamu change set genuinely
     * does not span the link, and scaling would stretch the partial coverage to look complete.
     */
    def scaleTiekamuM(tiekamuM: Double, tiekamuLinkLength: Double, viiteLinkLength: Double): Double = {
      if (tiekamuLinkLength <= 0.0 || !lengthsMatch(tiekamuLinkLength, viiteLinkLength)) tiekamuM
      else GeometryUtils.scaleMValue(tiekamuM, tiekamuLinkLength, viiteLinkLength)
    }

    /** Active linear locations of <i>linkId</i> that overlap the given M-range. The bounds may come in
     * either order: a TiekamuRoadLinkChange's old M-range is descending (oldStartM > oldEndM) when
     * the change runs against the old link's digitization direction. */
    def filterByLinkIdAndMValueRange(allLinearLocations: Seq[LinearLocation], linkId: String, filterMvalue1: Double, filterMvalue2: Double): List[LinearLocation] = {

      val mustStartBefore = math.max(filterMvalue1, filterMvalue2) - GeometryUtils.DefaultEpsilon
      val mustEndAfter = math.min(filterMvalue1, filterMvalue2) + GeometryUtils.DefaultEpsilon

      allLinearLocations
        .filter(ll =>
          ll.linkId == linkId &&
            ll.startMValue <= mustStartBefore &&
            ll.endMValue >= mustEndAfter &&
            ll.validTo.isEmpty
        )
        .sortBy(_.startMValue)
        .toList
    }

    def existsConnectingOrderNumber(orderNumbers: Seq[Double], otherOrderNumbers: Set[Double]): Boolean = {
      if (orderNumbers.isEmpty) return false
      val min = orderNumbers.min
      val max = orderNumbers.max
      otherOrderNumbers.contains(min - 1) || otherOrderNumbers.contains(max + 1)
    }

    def existsConnectingRoadway(roadway: Roadway, otherRoadways: Set[Roadway]): Boolean = {
      otherRoadways.map(_.addrMRange.start).contains(roadway.addrMRange.end) ||
      otherRoadways.map(_.addrMRange.end).contains(roadway.addrMRange.start)
    }

    def checkRoadAddressContinuityForSingleRoadway(linearLocations: Seq[LinearLocation], change: TiekamuRoadLinkChange): Boolean = {
      val linLocsGroupedByLinkId = linearLocations.groupBy(_.linkId)
      val orderNumbers = filterByLinkIdAndMValueRange(linearLocations, change.oldLinkId, change.oldStartM, change.oldEndM).map(_.orderNumber)
      val otherOrderNumbers = linLocsGroupedByLinkId.filter(grp => grp._1 != change.oldLinkId).map(_._2.map(_.orderNumber)).toSet.flatten
      existsConnectingOrderNumber(orderNumbers, otherOrderNumbers)
    }

    def checkRoadAddressContinuityBetweenRoadways(linearLocations: Seq[LinearLocation], change: TiekamuRoadLinkChange, roadwaysForLinearLocations: Set[Roadway]): Boolean = {
      val linLocsGroupedByLinkId = linearLocations.groupBy(_.linkId)
      val matchingLLs = filterByLinkIdAndMValueRange(linearLocations, change.oldLinkId, change.oldStartM, change.oldEndM)
      if (matchingLLs.isEmpty) return false
      // The changed link can carry linear locations of several roadways. Only one end of the link
      // touches the link it is merged with, so it is enough that ANY of the link's roadways connects
      // to a roadway of the other merged links. Taking just the first roadway (the one with the
      // smallest start M) reported "not continuous" whenever the merge happened at the other end.
      val roadwayNumbers = matchingLLs.map(_.roadwayNumber).distinct
      val roadways = roadwayNumbers.map(roadwayNumber =>
        roadwaysForLinearLocations.find(_.roadwayNumber == roadwayNumber).getOrElse(throw ViiteException(s"No Roadway found for linear location id: ${change.oldLinkId} startM: ${change.oldStartM} endM: ${change.oldEndM}"))
      )
      val otherRoadwayNumbers = linLocsGroupedByLinkId.filterNot(grp => grp._1 == change.oldLinkId).map(_._2.map(_.roadwayNumber)).toSet.flatten
      val otherRoadways = roadwaysForLinearLocations.filter(rw => otherRoadwayNumbers.contains(rw.roadwayNumber)).toSet
      roadways.exists(roadway => existsConnectingRoadway(roadway, otherRoadways))
    }

    def getMetaData(change: TiekamuRoadLinkChange, activeLinearLocations: Seq[LinearLocation], roadwaysForLinearLocations: Set[Roadway]): TiekamuRoadLinkErrorMetaData = {
      val errorLink = change.oldLinkId
      val errorLinearLocations = activeLinearLocations.filter(ll => ll.linkId == errorLink)
      val errorRoadwayNumbers = errorLinearLocations.map(_.roadwayNumber).toSet
      val errorRoadways = roadwaysForLinearLocations.filter(rw => errorRoadwayNumbers.contains(rw.roadwayNumber)).toSet
      val errorRoadsParts = errorRoadways.map(_.roadPart)
      if (errorRoadways.isEmpty)
        logger.warn(s"getMetaData: no roadways found for link $errorLink — linear locations: ${errorLinearLocations.map(_.id)}, roadway numbers: $errorRoadwayNumbers")
      TiekamuRoadLinkErrorMetaData(
        errorRoadsParts.headOption.getOrElse(RoadPart(0, 0)),
        errorRoadways.headOption.map(_.roadwayNumber).getOrElse(0L),
        errorLinearLocations.map(_.id),
        errorLink
      )
    }

    def validateCombinationCases(otherChangesWithSameNewLinkId: Seq[TiekamuRoadLinkChange], change: TiekamuRoadLinkChange, linearLocations: Seq[LinearLocation], roadwaysForLinearLocations: Set[Roadway]): Seq[TiekamuRoadLinkChangeError] = {
      def areHomogeneous(linearLocations: Set[LinearLocation]): Boolean = {
        val roadwayNumbers = linearLocations.map(_.roadwayNumber)
        val roadways = roadwaysForLinearLocations.filter(rw => roadwayNumbers.contains(rw.roadwayNumber))
        val roadGroups = roadways.groupBy(rw => (rw.roadPart, rw.track)) // We might need to check Administrative class here as well?
        roadGroups.size == 1
      }

      def changesInSameRoadway(linearLocations: Seq[LinearLocation]): Boolean = {
        val roadwayNumbers = linearLocations.map(_.roadwayNumber).toSet
        val roadways = roadwaysForLinearLocations.filter(rw => roadwayNumbers.contains(rw.roadwayNumber))
        val roadGroups = roadways.groupBy(rw => (rw.roadPart, rw.track))
        // More than one road group means the merged links are not on a single roadway, so the
        // between-roadways check is the right one. Picking roadGroups.head out of an unordered Map
        // made the chosen branch depend on hash order.
        if (roadGroups.size != 1) return false
        roadGroups.head._2.map(_.roadwayNumber).size == 1
      }

      def homogeneityValidationForCombinationCase(): Seq[TiekamuRoadLinkChangeError] = {
        val oldLinkIds = (otherChangesWithSameNewLinkId.map(_.oldLinkId) :+ change.oldLinkId).toSet
        val oldLinearLocations = oldLinkIds.flatMap(linkId => linearLocationsByLinkId.getOrElse(linkId, Seq.empty))
        val homogeneous = areHomogeneous(oldLinearLocations)

        if (!homogeneous) {
          Seq(
            TiekamuRoadLinkChangeError(
              "Two or more links with non-homogeneous road addresses (road number, road part number, track) cannot merge together.",
              change,
              getMetaData(change, linearLocations, roadwaysForLinearLocations)
            )
          )
        } else {
          Seq.empty
        }
      }

      def continuityValidationForCombinationCase(): Seq[TiekamuRoadLinkChangeError] = {
        val oldLinkIds = (otherChangesWithSameNewLinkId.map(_.oldLinkId) :+ change.oldLinkId).toSet
        val oldLinearLocations = oldLinkIds.toSeq.flatMap(linkId => linearLocationsByLinkId.getOrElse(linkId, Seq.empty))

        val continuityCheckPassed = if (changesInSameRoadway(oldLinearLocations)) {
          checkRoadAddressContinuityForSingleRoadway(oldLinearLocations, change)
        } else {
          checkRoadAddressContinuityBetweenRoadways(oldLinearLocations, change, roadwaysForLinearLocations)
        }

        if (!continuityCheckPassed) {
          Seq(
            TiekamuRoadLinkChangeError(
              "Road address not continuous, cannot merge links together.",
              change,
              getMetaData(change, linearLocations, roadwaysForLinearLocations)
            )
          )
        } else {
          Seq.empty
        }
      }

      /**
       * Merging links is blocked by exactly one thing: calibration points.
       *
       * Everything else a merge touches survives it. The linear locations are not fused — each old
       * link's linear locations are re-created on the new link over their own M-range, keeping their
       * order numbers, roadway numbers and addresses (see
       * LinkNetworkUpdater.linearLocationChangesDueToNetworkLinkReplace). Nodes and junctions are
       * link-agnostic: node_point and junction_point reference a roadway_point (roadway number and
       * address) and hold no link reference at all, so a junction at a merged boundary keeps pointing
       * at the same address whether that address sits at a link boundary or inside a link.
       *
       * Calibration points, on the other hand, are link-bound and carry a start/end flag.
       * LinearLocationDAO reads them back with a scalar subquery keyed on
       * (link_id, roadway_number, start_end), so a link may hold at most ONE start and ONE end
       * calibration point per roadway — a second one makes that subquery fail and the linear location
       * unreadable. Merging links whose shared boundary carries a calibration point produces exactly
       * that, because the merge copies every old link's calibration points onto the new link with
       * their start/end flag unchanged.
       *
       * So the merge is refused when, after merging, some roadway would end up with more than one
       * start or more than one end calibration point on the new link.
       */
      def calibrationPointValidationForCombinationCase(): Seq[TiekamuRoadLinkChangeError] = {
        val oldLinkIds = (otherChangesWithSameNewLinkId.map(_.oldLinkId) :+ change.oldLinkId).toSet
        val oldLinearLocations = oldLinkIds.toSeq.flatMap(linkId => linearLocationsByLinkId.getOrElse(linkId, Seq.empty))

        val conflictingRoadways = oldLinearLocations.groupBy(_.roadwayNumber).filter { case (_, linearLocationsOfRoadway) =>
          linearLocationsOfRoadway.count(_.startCalibrationPoint.isDefined) > 1 ||
            linearLocationsOfRoadway.count(_.endCalibrationPoint.isDefined) > 1
        }

        if (conflictingRoadways.nonEmpty) {
          logger.info(s"Merge into ${change.newLinkId} refused: roadway(s) ${conflictingRoadways.keys.mkString(", ")} would end up with several start or end calibration points on the merged link.")
          Seq(
            TiekamuRoadLinkChangeError(
              "Links cannot be merged together, the merged links have calibration point(s) on their shared boundary. (Cross road case)",
              change,
              getMetaData(change, linearLocations, roadwaysForLinearLocations)
            )
          )
        } else {
          Seq.empty
        }
      }

      homogeneityValidationForCombinationCase() ++ continuityValidationForCombinationCase() ++ calibrationPointValidationForCombinationCase()
    }

    time(logger, "Validating TiekamuRoadLinkChange sets") {
      val roadwaysForLinearLocations = roadwayDAO.fetchAllByRoadwayNumbers(linearLocations.map(_.roadwayNumber).toSet).toSet

      var tiekamuRoadLinkChangeErrors = new ListBuffer[TiekamuRoadLinkChangeError]()

      tiekamuRoadLinkChanges.foreach(change => {
        val oldLinkId = change.oldLinkId
        val newLinkId = change.newLinkId

        // The whole merge group of the new link, including changes on unaddressed old links: the
        // coverage arithmetic below is only correct when every source of the new link's geometry is
        // accounted for.
        // Coverage of the new link is measured over every row targeting the same link, whatever version
        // token it arrived with (see linkUuid).
        val changesWithNewLinkId = allChangesByNewLinkUuid.getOrElse(linkUuid(newLinkId), Seq.empty).distinct
        // The merge group, on the other hand, is the rows targeting this exact link version: rows of
        // another version of the link are the same link, not links being merged into it.
        val changesWithExactNewLinkId = allChangesByNewLinkId.getOrElse(newLinkId, Seq.empty).distinct
        // Every other change merging into the same new link, restricted to old links that carry road
        // addresses. The combination checks below reason about road addresses, so an unaddressed
        // sibling is not a merge partner for them. Comparing the change itself (instead of comparing
        // new M values) keeps siblings that happen to share a boundary M value with this change in the
        // merge group; dropping such a sibling used to make its linear locations look like an outsider
        // connecting between the merged links (false "Cross road case" error).
        val otherChangesWithSameNewLinkId = changesWithExactNewLinkId
          .filter(ch => addressedLinkIds.contains(ch.oldLinkId))
          .filterNot(ch => ch == change)

        val newLinkLength = linkLengthByLinkId.getOrElse(newLinkId,
          throw ViiteException(s"Missing new link from KGV/complementary link table. Cannot validate Tiekamu change infos without KGV/complementary road link. LinkId: ${newLinkId}, changeInfo: ${change}")
        )
        val linearLocationsWithOldLinkId = linearLocationsByLinkId.getOrElse(oldLinkId, Seq.empty)
        if (linearLocationsWithOldLinkId.isEmpty) {
          logger.warn(s"validateTiekamuRoadLinkChanges: no active linear locations for oldLinkId=$oldLinkId — skipping validation for this change")
        }
        if (linearLocationsWithOldLinkId.nonEmpty) {

        // check that the changeset actually has some changes in it
        if (change.oldLinkId == change.newLinkId &&
          change.oldStartM == change.newStartM &&
          change.oldEndM == change.newEndM &&
          !change.digitizationChange) {
          tiekamuRoadLinkChangeErrors += TiekamuRoadLinkChangeError("No changes found in the changeset ", change, getMetaData(change, linearLocations, roadwaysForLinearLocations))
        }

        // ---- Old link: the road addressed part of the link must be changed in full ----------------
        // Tiekamu M-values and Viite's linear location M-values are two different M-spaces for the
        // same link (Tiekamu m_arvo vs. KGV horizontallength), and they disagree by up to a few
        // metres on some links, so the Tiekamu values are remapped into Viite's M-space first.
        val allChangesWithOldLinkId = allChangesByOldLinkId.getOrElse(oldLinkId, Seq.empty)
        val addressedStartM = linearLocationsWithOldLinkId.map(_.startMValue).min
        val addressedEndM = linearLocationsWithOldLinkId.map(_.endMValue).max
        val viiteOldLinkLength = linkLengthByLinkId.getOrElse(oldLinkId, addressedEndM)
        // Falls back to the Viite length so that no scaling happens if the change set somehow holds no
        // row for this old link (max on an empty sequence would throw).
        val tiekamuOldLinkLength = allChangesWithOldLinkId.foldLeft(0.0)((acc, ch) => math.max(acc, math.max(ch.oldStartM, ch.oldEndM)))
        val oldRangesInViiteMSpace = allChangesWithOldLinkId.map(ch => (
          scaleTiekamuM(ch.oldStartM, tiekamuOldLinkLength, viiteOldLinkLength),
          scaleTiekamuM(ch.oldEndM, tiekamuOldLinkLength, viiteOldLinkLength)
        ))
        // Union, not sum: summing counted a gap-and-overlap pair as full coverage and counted
        // overlapping rows that point at different new links twice.
        val addressedLength = addressedEndM - addressedStartM
        val coveredAddressedLength = GeometryUtils.unionLength(
          GeometryUtils.clampIntervals(oldRangesInViiteMSpace, (addressedStartM, addressedEndM))
        )
        // An old link that is not changed in full means the change set for it is incomplete, which makes
        // the new link and combination verdicts below meaningless, so they are skipped in that case.
        if (!lengthsMatch(coveredAddressedLength, addressedLength)) {
          tiekamuRoadLinkChangeErrors += TiekamuRoadLinkChangeError("No partial changes allowed. The old link needs to have changes applied to the whole length of the old link", change, getMetaData(change, linearLocations, roadwaysForLinearLocations))
        }
        else {

        // ---- New link: the changes must account for the whole new link ----------------------------
        /** True when the given changes account for the new link's whole length. */
        def newSideCoversLength(changesIntoNewLink: Seq[TiekamuRoadLinkChange]): Boolean = {
          // A total length, not a span: the pieces of one new link do not always arrive on a common
          // M-scale (see linkUuid), so their M-ranges cannot be laid out side by side. Their lengths
          // still add up to the link's length, which is what is checked here. Exact duplicates and
          // overlapping rows for one mapping are already removed upstream, so nothing is counted twice.
          val totalLength = changesIntoNewLink.map(ch => math.abs(ch.newEndM - ch.newStartM)).sum
          changesIntoNewLink.nonEmpty && lengthsMatch(totalLength, newLinkLength)
        }
        // The changes to be applied are checked first, and only if they do not account for the new link
        // is the whole change set consulted: a new link that also receives an unaddressed old link, or
        // brand new geometry, adds up only once those rows are counted. Comparing with lengthsMatch
        // instead of exactly keeps the M-space deviation between the Tiekamu M-values and the new
        // link's length out of the result.
        val newSideCoversLink = newSideCoversLength(appliedChangesByNewLinkId.getOrElse(newLinkId, Seq.empty)) ||
          newSideCoversLength(changesWithNewLinkId)

        if (!newSideCoversLink) {
          tiekamuRoadLinkChangeErrors += TiekamuRoadLinkChangeError("No partial changes allowed. The new link needs to have changes applied to the whole length of the new link", change, getMetaData(change, linearLocations, roadwaysForLinearLocations))
        }
        // if there are combined links (A + B = C)
        // The combination checks reason about the road addresses on both sides of a merge boundary, so
        // they are only meaningful once the change set is known to cover both links completely.
        else if (otherChangesWithSameNewLinkId.nonEmpty) {
          tiekamuRoadLinkChangeErrors ++= validateCombinationCases(otherChangesWithSameNewLinkId, change, linearLocations, roadwaysForLinearLocations)
        }
        } // end else of the old link coverage check
        } // end if (linearLocationsWithOldLinkId.nonEmpty)
      })
      tiekamuRoadLinkChangeErrors
    }
  }

  /** If the change set includes an erroneous link update, then this function will filter out the whole road part (where the erroneous link update lies) from the change set.
   * @param activeLinearLocations Linearlocations that are in use on the road network at the moment
   * i.e. linear locations' valid_to IS NULL in the database AND the linear location is on a roadway that is on the current road network (roadways' valid_to IS NULL AND end_date IS NULL)
   */
  def filterOutErroneousParts(tiekamuRoadLinkChanges: Seq[TiekamuRoadLinkChange], activeLinearLocations: Seq[LinearLocation], tiekamuRoadLinkChangeErrors: Seq[TiekamuRoadLinkChangeError]): ((Seq[TiekamuRoadLinkChange], Seq[TiekamuRoadLinkChange]), Seq[LinearLocation]) = {
    val errorLinks = tiekamuRoadLinkChangeErrors.map(err => err.change.oldLinkId)
    val errorLinearLocations = activeLinearLocations.filter(ll => errorLinks.contains(ll.linkId))
    val errorRoadwayNumbers = errorLinearLocations.map(_.roadwayNumber)
    val errorRoadways = roadwayDAO.fetchAllByRoadwayNumbers(errorRoadwayNumbers.toSet)
    val errorRoadsParts = errorRoadways.map(r => {
      (r.roadPart)
    })
    logger.error(s"${tiekamuRoadLinkChangeErrors.size} errors found on road addresses: ${errorRoadsParts.toList}! Here is the list of errors: ${tiekamuRoadLinkChangeErrors.toList}")
    val affectedRoadwayNumbers = errorRoadsParts.flatMap(roadAndPart => roadwayDAO.fetchAllByRoadPart(roadAndPart)).map(_.roadwayNumber)
    val activeLinearLocationsWithoutAffected = activeLinearLocations.filterNot(ll => affectedRoadwayNumbers.contains(ll.roadwayNumber))
    val affectedLinkIds = activeLinearLocations.filter(ll => affectedRoadwayNumbers.contains(ll.roadwayNumber)).map(_.linkId)
    val (affectedTiekamuRoadLinkChanges, validTiekamuRoadLinkChanges) =  tiekamuRoadLinkChanges.partition(ch => affectedLinkIds.contains(ch.oldLinkId))
    ((validTiekamuRoadLinkChanges, affectedTiekamuRoadLinkChanges), activeLinearLocationsWithoutAffected)
  }

  /**
   * Resolves link ids KGV does not know to the KGV link of the same identity (kmtkid) whose version is
   * valid on <i>targetDate</i>.
   *
   * Tiekamu builds a link id as "<uuid>:<version>" but the version part is its own history numbering,
   * which is not always a KGV link version — non-numeric tokens such as "a", "b" and "c" appear, and
   * KGV has no such link. The uuid does identify a real link, so its versions are fetched by kmtkid and
   * the one covering the target date is picked. When a link has exactly one version, that one is used.
   *
   * @return (Tiekamu link id -> resolved KGV link id, the KGV links behind the resolved ids)
   */
  def resolveNewLinkIdsByKmtkId(unknownLinkIds: Set[String], targetDate: DateTime): (Map[String, String], Seq[RoadLink]) = {
    if (unknownLinkIds.isEmpty) return (Map.empty, Seq.empty)

    val kmtkIdsByLinkId = unknownLinkIds.map(linkId => linkId -> linkId.takeWhile(_ != ':')).toMap
    val candidates = kgvClient.roadLinkVersionsData.fetchByKmtkIds(kmtkIdsByLinkId.values.toSet)
    val candidatesByKmtkId = candidates.groupBy(_.linkId.takeWhile(_ != ':'))

    val resolved = kmtkIdsByLinkId.flatMap { case (linkId, kmtkId) =>
      val versions = candidatesByKmtkId.getOrElse(kmtkId, Seq.empty)
      val pick =
        if (versions.size <= 1) versions.headOption
        else pickVersionValidOn(versions, targetDate)
      pick.map(link => {
        logger.info(s"Resolved unknown link id $linkId to ${link.linkId} through kmtkid $kmtkId (${versions.size} version(s) known to KGV).")
        linkId -> link.linkId
      })
    }
    val unresolved = unknownLinkIds -- resolved.keySet
    if (unresolved.nonEmpty)
      logger.info(s"Could not resolve ${unresolved.size} link id(s) through kmtkid: ${unresolved.mkString(", ")}")

    val resolvedLinkIds = resolved.values.toSet
    (resolved, candidates.filter(link => resolvedLinkIds.contains(link.linkId)))
  }

  /**
   * The version of a link that is in force on <i>date</i>: the one that started last without starting
   * after the date. KGV's link versions are consecutive, so the next version's start is the previous
   * one's end. A link's start time is its <i>versionstarttime</i>, carried in RoadLink.modifiedAt.
   * Falls back to the earliest version when every version starts after the date.
   */
  def pickVersionValidOn(versions: Seq[RoadLink], date: DateTime): Option[RoadLink] = {
    def startTime(roadLink: RoadLink): Option[DateTime] =
      roadLink.modifiedAt.flatMap(modified => scala.util.Try(new DateTime(modified)).toOption)

    val started = versions.filter(link => startTime(link).exists(start => !start.isAfter(date)))
    if (started.nonEmpty) Some(started.maxBy(link => startTime(link).get.getMillis))
    else versions.sortBy(link => startTime(link).map(_.getMillis).getOrElse(Long.MaxValue)).headOption
  }

  /** The change rows with their new link ids replaced by the resolved KGV link ids. */
  def withResolvedNewLinkIds(changes: Seq[TiekamuRoadLinkChange], resolvedNewLinkIds: Map[String, String]): Seq[TiekamuRoadLinkChange] = {
    if (resolvedNewLinkIds.isEmpty) changes
    else changes.map(ch =>
      resolvedNewLinkIds.get(ch.newLinkId) match {
        case Some(resolvedLinkId) => ch.copy(newLinkId = resolvedLinkId)
        case None => ch
      }
    )
  }

  def createChangeSetsAndErrorsList(previousDate: DateTime, newDate: DateTime): (Seq[LinkNetworkChange], Seq[TiekamuRoadLinkChangeError], Seq[TiekamuRoadLinkChange]) = {
    runWithTransaction {
      val activeLinearLocations = linearLocationDAO.fetchActiveLinearLocationsWithRoadAddresses() // get linear locations that are on active road addresses
      val (rawTiekamuRoadLinkChanges, rawAllTiekamuRoadLinkChanges) = createTiekamuRoadLinkChangeSets(previousDate: DateTime, newDate: DateTime, activeLinearLocations)

      //get the new and the old linkIds to Set[String]
      // Only the links of the changes that are actually applied are fetched and required to exist.
      // The rest of the change set contributes M-lengths to the coverage checks and nothing else: a
      // sibling row can name a link KGV does not know at all (Tiekamu reports the pieces of one link
      // under version tokens of its own, e.g. "<uuid>:b"), and demanding those from KGV both slowed
      // the run down by tens of minutes and aborted it outright.
      val newLinkIds = rawTiekamuRoadLinkChanges.map(_.newLinkId).filter(_ != null).toSet
      val oldLinkIds = rawTiekamuRoadLinkChanges.map(_.oldLinkId).filter(_ != null).toSet
      // fetch roadLinks from KGV these are used for getting geometry and link lengths
      val kgvRoadLinks = kgvClient.roadLinkVersionsData.fetchByLinkIds(newLinkIds ++ oldLinkIds)

      val unknownNewLinkIds = {
        if (newLinkIds.nonEmpty) {
          val knownLinkIds = (kgvRoadLinks ++ kgvClient.complementaryData.fetchByLinkIds(newLinkIds)).map(_.linkId).toSet
          newLinkIds.filterNot(knownLinkIds.contains)
        } else {
          Set.empty[String]
        }
      }

      // Tiekamu does not always report the KGV link version in a link id: the version part can come
      // from its own history numbering ("<uuid>:a", "<uuid>:b", "<uuid>:c"), which KGV does not know,
      // and which therefore has no length or geometry to build the new link from. Such an id still
      // names a real link, so it is resolved through KGV's kmtkid attribute to the version that is
      // valid on the target date, and the change rows are rewritten to use that id.
      val (resolvedNewLinkIds, resolvedKgvLinks) = resolveNewLinkIdsByKmtkId(unknownNewLinkIds, newDate)
      // Normalised again: resolving a link id can turn a row into a no-op (e.g. "X:1 -> X:a" becomes
      // "X:1 -> X:1"), which the first pass could not have seen.
      val tiekamuRoadLinkChanges = normaliseChangeSet(withResolvedNewLinkIds(rawTiekamuRoadLinkChanges, resolvedNewLinkIds), "resolved")
      val allTiekamuRoadLinkChanges = normaliseChangeSet(withResolvedNewLinkIds(rawAllTiekamuRoadLinkChanges, resolvedNewLinkIds), "resolved, full set")
      val kgvRoadLinksWithResolved = kgvRoadLinks ++ resolvedKgvLinks

      val nonExistentNewLinkIds = unknownNewLinkIds -- resolvedNewLinkIds.keySet

      if (nonExistentNewLinkIds.nonEmpty) {
        logger.info(s"Some link ids (${nonExistentNewLinkIds}) were not found in KGV or in Viite complementary link table. Searching from VKM next..")
        nonExistentNewLinkIds.foreach(linkId => {
          val complementaryLinkFromVKM = vkmClient.fetchComplementaryLinkFromVKM(linkId)
          if (complementaryLinkFromVKM.nonEmpty) {
            logger.info(s"Found complementaryLink from VKM: ${complementaryLinkFromVKM.get}, adding to complementary link table in Viite.")
            complementaryLinkDAO.create(complementaryLinkFromVKM.get)
          } else {
            throw ViiteException("Couldn't find new link id in KGV, Viite complementary link table, or VKM complementary links.")
          }
        })
      }

      val complementaryLinks = {
        if ((newLinkIds ++ oldLinkIds).nonEmpty) {
          kgvClient.complementaryData.fetchByLinkIds(newLinkIds ++ oldLinkIds)
        } else {
          Seq.empty[RoadLink]
        }
      }

      val tiekamuRoadLinkChangeErrors = validateTiekamuRoadLinkChanges(tiekamuRoadLinkChanges, activeLinearLocations, kgvRoadLinksWithResolved, complementaryLinks, allTiekamuRoadLinkChanges)

      var skippedTiekamuRoadLinkChanges = Seq.empty[TiekamuRoadLinkChange]
      val (validTiekamuRoadLinkChanges, validActiveLinearLocations) = {
        if (tiekamuRoadLinkChangeErrors.nonEmpty) {
          val ((validTiekamuRoadLinkChanges, affectedTiekamuRoadLinkChanges), validActiveLinearLocations) =  filterOutErroneousParts(tiekamuRoadLinkChanges, activeLinearLocations, tiekamuRoadLinkChangeErrors)
          skippedTiekamuRoadLinkChanges = affectedTiekamuRoadLinkChanges
          (validTiekamuRoadLinkChanges, validActiveLinearLocations)
        } else {
          (tiekamuRoadLinkChanges, activeLinearLocations)
        }
      }

      val viiteChangeSets = createViiteLinkNetworkChanges(validTiekamuRoadLinkChanges, validActiveLinearLocations, kgvRoadLinksWithResolved, complementaryLinks)
      (viiteChangeSets, tiekamuRoadLinkChangeErrors, skippedTiekamuRoadLinkChanges)
    }
  }

  /**
   * Initiates the update process for the link network, either as a single batch job or divided into daily incremental updates.
   *
   * @param previousDate   The starting date of the current link network state.
   * @param newDate        The target date to which the link network should be updated.
   * @param processPerDay  If true, the update is performed in daily intervals; if false, the entire range is processed as a single batch.
   */
  def initiateLinkNetworkUpdates(previousDate: DateTime, newDate: DateTime, processPerDay: Boolean): Unit = {
    time(logger, s"Link network update from ${previousDate} to ${newDate}") {
      try {
        var currentLinkNetworkStateDate = previousDate
        var skippedTiekamuRoadLinkChanges = Seq[TiekamuRoadLinkChange]()

        if (processPerDay) {
          while (currentLinkNetworkStateDate.isBefore(newDate)) {
            val nextDateTime = currentLinkNetworkStateDate.plusDays(1)
            skippedTiekamuRoadLinkChanges ++= updateLinkNetwork(currentLinkNetworkStateDate, nextDateTime)
            currentLinkNetworkStateDate = nextDateTime
          }
        } else {
          skippedTiekamuRoadLinkChanges ++= updateLinkNetwork(currentLinkNetworkStateDate, newDate)
        }

        if (skippedTiekamuRoadLinkChanges.nonEmpty) {
          // SkippedTiekamuRoadLinkChanges-yyyy-MM-dd-yyyy-MM-dd-yyyy-MM-dd:hh:mm:ss (SkippedTiekamuRoadLinkChanges-previousDate-newDate-currentTimeStamp)
          val s3SkippedChangeSetsName = s"SkippedTiekamuRoadLinkChanges-${previousDate.getYear}-${previousDate.getMonthOfYear}-${previousDate.getDayOfMonth}-" +
                                        s"${newDate.getYear}-${newDate.getMonthOfYear}-${newDate.getDayOfMonth}-${DateTime.now()}"
          val jsonSkippedChanges = Json(DefaultFormats).write(skippedTiekamuRoadLinkChanges.map(skippedChange => skippedTiekamuRoadLinkChangeToMap(skippedChange)))
          saveToS3(bucketName, s3SkippedChangeSetsName, jsonSkippedChanges, "json") // save the error details to S3
        }
      } catch {
        case ex: ViiteException =>
          logger.error(s"Link network update from ${previousDate} to ${newDate} failed with ${ex}")
        case e: Exception =>
          logger.error(s"An error occurred while updating road link network from ${previousDate} to ${newDate}: ${e}")
      }
    }
  }

  def updateLinkNetwork(previousDate: DateTime, newDate: DateTime): Seq[TiekamuRoadLinkChange] = {
    time(logger, s"Updating road link network from ${previousDate} to ${newDate}") {
      val (viiteChangeSets, tiekamuRoadLinkChangeErrors, skippedTiekamuRoadLinkChanges) = createChangeSetsAndErrorsList(previousDate, newDate)

      // yyyy-MM-dd-yyyy-MM-dd
      val changeDateString =  s"${previousDate.getYear}-${previousDate.getMonthOfYear}-${previousDate.getDayOfMonth}-" +
                              s"${newDate.getYear}-${newDate.getMonthOfYear}-${newDate.getDayOfMonth}"

      if (tiekamuRoadLinkChangeErrors.nonEmpty) {
        val jsonErrorParts = Json(DefaultFormats).write(tiekamuRoadLinkChangeErrors.map(error => tiekamuRoadLinkChangeErrorToMap(error)))
        val s3ChangeSetErrorsName = s"${previousDate.getDayOfMonth}-${previousDate.getMonthOfYear}-${previousDate.getYear}-" +
                                    s"${newDate.getDayOfMonth}-${newDate.getMonthOfYear}-${newDate.getYear}-${DateTime.now()}-Errors"
        saveToS3(bucketName, s3ChangeSetErrorsName, jsonErrorParts, "json") // save the error details to S3
      }

      if (viiteChangeSets.nonEmpty) {
        val jsonChangeSets = Json(DefaultFormats).write(viiteChangeSets.map(change => linkNetworkChangeToMap(change)))
        // Samuutus-yyyy-MM-dd-yyyy-MM-dd (Samuutus-previousDate-newDate)
        val changeSetNameForViiteDB = s"Samuutus-" + changeDateString
        if (changeSetNameForViiteDB.length > 32)
          throw ViiteException(s"ChangeSetName: ${changeSetNameForViiteDB} too long, maximum number of characters allowed is 32")

        // ViiteChangeSets-yyyy-MM-dd-yyyy-MM-dd-yyyy-MM-dd:hh:mm:ss (ViiteChangeSets-previousDate-newDate-currentTimeStamp)
        val changeSetNameForS3Bucket = "ViiteChangeSets" + "-" + changeDateString + "-" + DateTime.now()
        saveToS3(bucketName, changeSetNameForS3Bucket, jsonChangeSets, "json")

        linkNetworkUpdater.persistLinkNetworkChanges(viiteChangeSets, changeSetNameForViiteDB, newDate, LinkGeomSource.NormalLinkInterface)
        logger.info(s"${viiteChangeSets.size} links updated successfully!")
      } else {
        logger.info(s"Zero links were updated!")
      }
      skippedTiekamuRoadLinkChanges
    }
  }
}
