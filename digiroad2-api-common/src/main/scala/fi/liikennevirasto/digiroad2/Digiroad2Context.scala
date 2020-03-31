package fi.liikennevirasto.digiroad2

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import fi.liikennevirasto.digiroad2.client.vvh.VVHClient
import fi.liikennevirasto.digiroad2.municipality.MunicipalityProvider
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.user.UserProvider
import fi.liikennevirasto.digiroad2.util.ViiteProperties
import fi.liikennevirasto.viite.{NodesAndJunctionsService, ProjectService, RoadAddressService, RoadCheckOptions, RoadNameService, RoadNetworkService}
import fi.liikennevirasto.viite.util.JsonSerializer
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.RoadAddressFiller.ChangeSet
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

class RoadAddressUpdater(roadAddressService: RoadAddressService) extends Actor {
  def receive: PartialFunction[Any, Unit] = {
    case w: ChangeSet => roadAddressService.updateChangeSet(w)
    case _                    => println("roadAddressUpdater: Received unknown message")
  }
}

//class RoadAddressUpdater(roadAddressService: RoadAddressService) extends Actor {
//  def receive = {
//    case w: Seq[any] => roadAddressService.createUnaddressedRoadLink(w.asInstanceOf[Seq[UnaddressedRoadLink]])
//    case _                    => println("roadAddressUpdater: Received unknown message")
//  }
//}
//
//class RoadAddressMerger(roadAddressService: RoadAddressService) extends Actor {
//  def receive = {
//    case w: RoadAddressMerge => roadAddressService.mergeRoadAddress(w.asInstanceOf[RoadAddressMerge])
//    case _                    => println("roadAddressMerger: Received unknown message")
//  }
//}
//
//class RoadAddressAdjustment(roadAddressService: RoadAddressService) extends Actor {
//  def receive = {
//    case w: Seq[any] => roadAddressService.saveAdjustments(w.asInstanceOf[Seq[LinearLocationAdjustment]])
//    case _                    => println("roadAddressUpdater: Received unknown message")
//  }
//}


class RoadNetworkChecker(roadNetworkService: RoadNetworkService) extends Actor {
  def receive: PartialFunction[Any, Unit] = {
    case w: RoadCheckOptions =>  roadNetworkService.checkRoadAddressNetwork(w)
    case _ => println("roadAddressChecker: Received unknown message")
  }
}

object Digiroad2Context {
  val Digiroad2ServerOriginatedResponseHeader = "Digiroad2-Server-Originated-Response"

  val system = ActorSystem("Digiroad2")
  import system.dispatcher
  val logger: Logger = LoggerFactory.getLogger(getClass)
  system.scheduler.schedule(FiniteDuration(2, TimeUnit.MINUTES), FiniteDuration(1, TimeUnit.MINUTES)) { // first query after 2 minutes, then once per minute
    try {
      projectService.updateProjectsWaitingResponseFromTR()
    } catch {
      case  NonFatal(ex) =>
        logger.error("Exception at TR checks:" + ex.getMessage)
        System.err.println("Exception at TR checks: " + ex.getMessage)
    }
  }

  system.scheduler.schedule(FiniteDuration(5, TimeUnit.MINUTES), FiniteDuration(5, TimeUnit.MINUTES)) { // first query after 5 minutes, then once per 5 minute
    try {
      projectService.sendProjectsInWaiting()
    } catch {
      case ex: Exception => System.err.println("Exception when sending projects to TR: " + ex.getMessage)
    }
  }

//  val roadAddressUpdater = system.actorOf(Props(classOf[RoadAddressUpdater], roadAddressService), name = "roadAddressUpdater")
//  eventbus.subscribe(roadAddressUpdater, "roadAddress:persistUnaddressedRoadLink")

//  val roadAddressMerger = system.actorOf(Props(classOf[RoadAddressMerger], roadAddressService), name = "roadAddressMerger")
//  eventbus.subscribe(roadAddressMerger, "roadAddress:mergeRoadAddress")

//  val roadAddressAdjustment = system.actorOf(Props(classOf[RoadAddressAdjustment], roadAddressService), name = "roadAddressAdjustment")
//  eventbus.subscribe(roadAddressAdjustment, "roadAddress:persistAdjustments")

//  val roadAddressFloater = system.actorOf(Props(classOf[RoadAddressFloater], roadAddressService), name = "roadAddressFloater")
//  eventbus.subscribe(roadAddressFloater, "roadAddress:floatRoadAddress")

  val roadAddressUpdater: ActorRef = system.actorOf(Props(classOf[RoadAddressUpdater], roadAddressService), name = "roadAddressUpdater")
  eventbus.subscribe(roadAddressUpdater, "roadAddress:persistChangeSet")

  val roadNetworkChecker: ActorRef = system.actorOf(Props(classOf[RoadNetworkChecker], roadNetworkService), name = "roadNetworkChecker")
  eventbus.subscribe(roadNetworkChecker, "roadAddress:RoadNetworkChecker")

  lazy val roadAddressService: RoadAddressService = {
    new RoadAddressService(roadLinkService, roadwayDAO, linearLocationDAO, roadNetworkDAO, roadwayPointDAO, nodePointDAO, junctionPointDAO, roadwayAddressMapper, eventbus, ViiteProperties.vvhRoadlinkFrozen)
  }

  lazy val projectService: ProjectService = {
    new ProjectService(roadAddressService, roadLinkService, nodesAndJunctionsService, roadwayDAO,
      roadwayPointDAO, linearLocationDAO, projectDAO, projectLinkDAO,
      nodeDAO, nodePointDAO, junctionPointDAO, projectReservedPartDAO, roadwayChangesDAO,
      roadwayAddressMapper, eventbus, ViiteProperties.vvhRoadlinkFrozen)
  }

  lazy val roadNetworkService: RoadNetworkService = {
    new RoadNetworkService
  }

  lazy val roadNameService : RoadNameService = {
    new RoadNameService
  }

    lazy val nodesAndJunctionsService : NodesAndJunctionsService = {
    new NodesAndJunctionsService(roadwayDAO, roadwayPointDAO, linearLocationDAO, nodeDAO, nodePointDAO, junctionDAO, junctionPointDAO, roadwayChangesDAO)
  }

  lazy val authenticationTestModeEnabled: Boolean = {
    ViiteProperties.authenticationTestMode
  }

  lazy val authenticationTestModeUser: String = {
    ViiteProperties.authenticationTestUser
  }

  lazy val authenticationTestModeUser: String = {
    properties.getProperty("digiroad2.authenticationTestUser", "")
  }

  lazy val userProvider: UserProvider = {
    Class.forName(ViiteProperties.userProvider).newInstance().asInstanceOf[UserProvider]
  }

  lazy val municipalityProvider: MunicipalityProvider = {
    Class.forName(ViiteProperties.municipalityProvider).newInstance().asInstanceOf[MunicipalityProvider]
  }

  lazy val eventbus: DigiroadEventBus = {
    Class.forName(ViiteProperties.eventBus).newInstance().asInstanceOf[DigiroadEventBus]
  }

  lazy val vvhClient: VVHClient = {
    new VVHClient(ViiteProperties.vvhRestApiEndPoint)
  }

  lazy val roadLinkService: RoadLinkService = {
    new RoadLinkService(vvhClient, eventbus, new JsonSerializer, useFrozenLinkInterface)
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

  lazy val revision: String = {
    ViiteProperties.revision
  }

  lazy val deploy_date: String = {
    ViiteProperties.latestDeploy
  }

  lazy val useFrozenLinkInterface: Boolean = {
    ViiteProperties.vvhRoadlinkFrozen
  }

  val env = ViiteProperties.env
}
