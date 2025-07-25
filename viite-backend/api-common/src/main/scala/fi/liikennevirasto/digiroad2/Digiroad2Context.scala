package fi.liikennevirasto.digiroad2

import java.util.concurrent.TimeUnit
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import fi.liikennevirasto.digiroad2.client.kgv.KgvRoadLink
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.user.UserProvider
import fi.liikennevirasto.digiroad2.util.ViiteProperties
import fi.liikennevirasto.viite.{AwsService, APIServiceForNodesAndJunctions, NodesAndJunctionsService, ProjectService, RoadAddressService, RoadNameService, RoadNetworkValidator, UserService}
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.RoadAddressFiller.ChangeSet
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import fi.liikennevirasto.viite.util.{DataImporter, JsonSerializer}
import fi.vaylavirasto.viite.dynamicnetwork.{DynamicRoadNetworkService, LinkNetworkUpdater}
import org.slf4j.{Logger, LoggerFactory}
import fi.liikennevirasto.digiroad2.util.DatabaseMigration
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import org.scalatra.{InternalServerError, Ok}
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

class RoadAddressUpdater(roadAddressService: RoadAddressService) extends Actor {
  def receive: PartialFunction[Any, Unit] = {
    case w: ChangeSet => roadAddressService.updateChangeSet(w)
    case _                    => println("roadAddressUpdater: Received unknown message")
  }
}

object Digiroad2Context {
  val Digiroad2ServerOriginatedResponseHeader = "Digiroad2-Server-Originated-Response"

  private val system = ActorSystem("Digiroad2")
  import system.dispatcher
  val logger: Logger = LoggerFactory.getLogger(getClass)

  /** Runs periodically to check, if there are projects to be accepted to the road network,
    * that is, to be updated to Viite DB.
    * First query after startup after 2 minutes, then once every minute. */
  system.scheduler.schedule(FiniteDuration(2, TimeUnit.MINUTES), FiniteDuration(1, TimeUnit.MINUTES)) {
    try {
      projectService.preserveSingleProjectToBeTakenToRoadNetwork()
    } catch {
      case  NonFatal(ex) =>
        logger.error("Exception at preserving a project :" + ex.getMessage)
        System.err.println("Exception at preserving a project: " + ex.getMessage)
    }
  }

  /* Run flyway migrate once after start in non-local envs  */
  system.scheduler.scheduleOnce(FiniteDuration(10, TimeUnit.SECONDS)) {
    if (ViiteProperties.env.trim.toLowerCase != "local") {
      time(logger, "Scheduled once flyway_migrate") {
        try {
          val outOfOrder = true
          DatabaseMigration.migrate(outOfOrder)
          logger.info("Flyway migrate successful.")
          Ok("Flyway migrate successful.\n")
        } catch {
          case e: Exception =>
            logger.error("Flyway migrate failed.", e)
            InternalServerError(s"Flyway migrate failed: ${e.getMessage}")
        }
      }
    }
  }

  val roadAddressUpdater: ActorRef = system.actorOf(Props(classOf[RoadAddressUpdater], roadAddressService), name = "roadAddressUpdater")
  eventbus.subscribe(roadAddressUpdater, "roadAddress:persistChangeSet")

  lazy val roadAddressService: RoadAddressService =
    new RoadAddressService(
                            roadLinkService,
                            roadwayDAO,
                            linearLocationDAO,
                            roadNetworkDAO,
                            roadwayPointDAO,
                            nodePointDAO,
                            junctionPointDAO,
                            roadwayAddressMapper,
                            eventbus,
                            ViiteProperties.kgvRoadlinkFrozen
                          )

  lazy val projectService: ProjectService = {
    new ProjectService(
                        roadAddressService,
                        roadLinkService,
                        nodesAndJunctionsService,
                        roadwayDAO,
                        roadwayPointDAO,
                        linearLocationDAO,
                        projectDAO,
                        projectLinkDAO,
                        nodeDAO,
                        nodePointDAO,
                        junctionPointDAO,
                        projectReservedPartDAO,
                        roadwayChangesDAO,
                        roadwayAddressMapper,
                        eventbus,
                        ViiteProperties.kgvRoadlinkFrozen
                      )
  }

  lazy val roadNameService : RoadNameService = {
    new RoadNameService
  }

  lazy val nodesAndJunctionsService: NodesAndJunctionsService = {
    new NodesAndJunctionsService(
                                  roadwayDAO,
                                  roadwayPointDAO,
                                  linearLocationDAO,
                                  nodeDAO,
                                  nodePointDAO,
                                  junctionDAO,
                                  junctionPointDAO,
                                  roadwayChangesDAO,
                                  projectReservedPartDAO
                                )
  }

  lazy val APIServiceForNodesAndJunctions: APIServiceForNodesAndJunctions = {
    new APIServiceForNodesAndJunctions(
                                  roadwayDAO,
                                  linearLocationDAO,
                                  nodeDAO,
                                  junctionDAO
    )
  }

  lazy val roadNetworkValidator: RoadNetworkValidator = {
    new RoadNetworkValidator()
  }

  lazy val authenticationTestModeEnabled: Boolean = {
    ViiteProperties.authenticationTestMode
  }

  lazy val authenticationTestModeUser: String = {
    ViiteProperties.authenticationTestUser
  }

  lazy val userProvider: UserProvider = {
    Class.forName(ViiteProperties.userProvider).getDeclaredConstructor().newInstance().asInstanceOf[UserProvider]
  }

  lazy val eventbus: DigiroadEventBus = {
    Class.forName(ViiteProperties.eventBus).getDeclaredConstructor().newInstance().asInstanceOf[DigiroadEventBus]
  }

  lazy val kgvRoadLinkClient: KgvRoadLink = new KgvRoadLink()

  lazy val roadLinkService: RoadLinkService = {
    new RoadLinkService(kgvRoadLinkClient, eventbus, new JsonSerializer, useFrozenLinkInterface)
  }

  lazy val roadwayDAO: RoadwayDAO = {
    new RoadwayDAO
  }

  lazy val linearLocationDAO: LinearLocationDAO = {
    new LinearLocationDAO
  }

  lazy val roadwayPointDAO: RoadwayPointDAO = {
    new RoadwayPointDAO
  }

  lazy val nodeDAO: NodeDAO = {
    new NodeDAO
  }

  lazy val junctionDAO: JunctionDAO = {
    new JunctionDAO
  }

  lazy val nodePointDAO: NodePointDAO = {
    new NodePointDAO
  }

  lazy val junctionPointDAO: JunctionPointDAO = {
    new JunctionPointDAO
  }

  lazy val roadNetworkDAO: RoadNetworkDAO = {
    new RoadNetworkDAO
  }

  lazy val roadwayChangesDAO: RoadwayChangesDAO = {
    new RoadwayChangesDAO
  }

  lazy val projectDAO: ProjectDAO = {
    new ProjectDAO
  }

  lazy val projectLinkDAO: ProjectLinkDAO = {
    new ProjectLinkDAO
  }

  lazy val projectReservedPartDAO : ProjectReservedPartDAO = {
    new ProjectReservedPartDAO
  }

  lazy val roadwayAddressMapper: RoadwayAddressMapper = {
    new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)
  }

  lazy val dataImporter: DataImporter = new DataImporter

  lazy val linkNetworkUpdater: LinkNetworkUpdater = {
    new LinkNetworkUpdater
  }

  lazy val deploy_date: String = {
    ViiteProperties.latestDeploy
  }

  lazy val useFrozenLinkInterface: Boolean = {
    ViiteProperties.kgvRoadlinkFrozen
  }

  lazy val awsService: AwsService = {
    new AwsService()
  }

  lazy val dynamicRoadNetworkService: DynamicRoadNetworkService = {
    new DynamicRoadNetworkService(linearLocationDAO, roadwayDAO, kgvRoadLinkClient, awsService, linkNetworkUpdater)
  }

  lazy val userService: UserService = {
    new UserService(userProvider)
  }

  val env = ViiteProperties.env
}
