package tcep.graph

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{ActorContext, ActorRef, Props}
import akka.cluster.{Cluster, Member, MemberStatus}
import org.discovery.vivaldi.Coordinates
import org.slf4j.LoggerFactory
import tcep.data.Events.Event
import tcep.data.Queries._
import tcep.factories.NodeFactory
import tcep.graph.nodes._
import tcep.graph.nodes.traits.Mode
import tcep.graph.nodes.traits.Mode.Mode
import tcep.graph.nodes.traits.Node.Dependencies
import tcep.graph.qos.MonitorFactory
import tcep.graph.transition._
import tcep.placement.{HostInfo, PlacementStrategy}
import tcep.simulation.tcep.GUIConnector
import tcep.utils.SpecialStats

import scala.collection.SortedSet
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

/**
  * Created by raheel
  * on 15/08/2017.
  *
  * Extracts the Operator Graph from Base Query
  */
class QueryGraph(context: ActorContext,
                 cluster: Cluster,
                 query: Query,
                 publishers: Map[String, ActorRef],
                 createdCallback: Option[CreatedCallback],
                 monitors: Array[MonitorFactory]) {

  val log = LoggerFactory.getLogger(getClass)
  var mapek: MAPEK = new MAPEK(context, query, this)
  var placementStrategy: PlacementStrategy = mapek.getBestPlacementStrategy(context, cluster)

  private def startDeployment(q: Query, mode: Mode,
                              publishers: Map[String, ActorRef],
                              createdCallback: Option[CreatedCallback],
                              eventCallback: Option[EventCallback],
                              monitors: Array[MonitorFactory]
                             ): ActorRef = {

    placementStrategy.initialize(cluster, false, Some(publishers))
    val startTime = System.currentTimeMillis()
    SpecialStats.debug(s"$this", s"starting initial virtual placement")
    val deployment: Future[ActorRef] = if(placementStrategy.hasInitialPlacementRoutine()) { // some placement algorithms calculate an initial placement with global knowledge, instead of calculating the optimal node one after another
      val initialOperatorPlacementRequest = placementStrategy.initialVirtualOperatorPlacement(cluster, q)
      initialOperatorPlacementRequest.onComplete {
        case Success(value) => SpecialStats.debug(s"$this", s"initial deployment virtual placement took ${System.currentTimeMillis() - startTime}ms")
        case Failure(exception) => SpecialStats.debug(s"$this", s"initial deployment virtual placement failed after ${System.currentTimeMillis() - startTime}ms, cause: \n $exception")
      }
      for {
        initialOperatorPlacement <- initialOperatorPlacementRequest
        deployment <-deployGraph(q, mode, publishers, createdCallback, eventCallback, monitors, initialPlacement = initialOperatorPlacement)
      } yield deployment
    } else {
        deployGraph(q, mode, publishers, createdCallback, eventCallback, monitors)
    }

    val rootOperator = Await.result(deployment, new FiniteDuration(120, TimeUnit.SECONDS)) // block here to wait until deployment is finished
    SpecialStats.debug(s"$this", s"initial deployment took ${System.currentTimeMillis() - startTime}ms")
    rootOperator
  }

  private def deployGraph(q: Query, mode: Mode,
                          publishers: Map[String, ActorRef],
                          createdCallback: Option[CreatedCallback],
                          eventCallback: Option[EventCallback],
                          monitors: Array[MonitorFactory],
                          initialPlacement: Map[Query, Coordinates] = null
                         ): Future[ActorRef] = {

    val rootOperator: Future[ActorRef] = q match {

      case query: StreamQuery => {
        deploy(mode, context, cluster, monitors, query, initialPlacement, createdCallback, eventCallback, publishers(query.publisherName))
      }

      case query: SequenceQuery => {
        deploy(mode, context, cluster, monitors, query, initialPlacement, createdCallback, eventCallback,
          publishers(query.s1.publisherName), publishers(query.s2.publisherName))
      }

      case query: UnaryQuery => {
        deployGraph(query.sq, mode, publishers, None, None, monitors, initialPlacement) map { parentDeployment =>
          deploy(mode, context, cluster, monitors, query, initialPlacement, createdCallback, eventCallback, parentDeployment)
        } flatten
      }

      case query: BinaryQuery => {
        // declare outside for comprehension so that futures start in parallel
        val parent1Deployment = deployGraph(query.sq1, mode, publishers, None, None, monitors, initialPlacement)
        val parent2Deployment = deployGraph(query.sq2, mode, publishers, None, None, monitors, initialPlacement)
        val deployment: Future[Future[ActorRef]] = for {
          parent1 <- parent1Deployment
          parent2 <- parent2Deployment
        } yield deploy(mode, context, cluster, monitors, query, initialPlacement, createdCallback, eventCallback, parent1, parent2)
        deployment.flatten
      }
      case other => throw new RuntimeException(s"unknown query type! $other")
    }
    rootOperator
  }

  private def deploy(mode: Mode,
                     context: ActorContext,
                     cluster: Cluster,
                     factories: Array[MonitorFactory],
                     operator: Query,
                     initialOperatorPlacement: Map[Query, Coordinates],
                     createdCallback: Option[CreatedCallback],
                     eventCallback: Option[EventCallback],
                     parentOperators: ActorRef*): Future[ActorRef] = {

    SpecialStats.debug(s"$this", s"deploying operator $operator in mode $mode with placement strategy ${placementStrategy.strategyName()}")
    var hostInfoResult: HostInfo = null
    val deployment: Future[ActorRef] =
      if(placementStrategy.hasInitialPlacementRoutine() && initialOperatorPlacement.contains(operator)) {

        for {
          hostInfo <- placementStrategy.findHost(initialOperatorPlacement(operator), null, operator, parentOperators.toList)
          deployedOperator <- deployNode(mode, context, cluster, operator, createdCallback, eventCallback, hostInfo, false, None, parentOperators)
        } yield {
          if(mapek.knowledge.hasReliabilityReq()) {  // for now, start duplicate on self (just like relaxation does in this case, see findOptimalNodes())
            val backup = deployNode(mode, context, cluster, operator, createdCallback, eventCallback, HostInfo(cluster.selfMember, operator, hostInfo.operatorMetrics), true, None, parentOperators)
          }
          hostInfoResult = hostInfo
          deployedOperator
        }

      } else { // no initial placement or operator is missing
        if (mapek.knowledge.hasReliabilityReq()) {
          for {
            hostInfo <- placementStrategy.findOptimalNodes(context, cluster, Dependencies(parentOperators.toList, List()), HostInfo(cluster.selfMember, operator), operator)
            deployedOperator <- deployNode(mode, context, cluster, operator, createdCallback, eventCallback, hostInfo._1, false, None, parentOperators)
          } yield {
            hostInfoResult = hostInfo._1
            // for now, start duplicate on self (just like relaxation does in this case)
            val backup = deployNode(mode, context, cluster, operator, createdCallback, eventCallback, HostInfo(cluster.selfMember, operator, hostInfo._2.operatorMetrics), true, None, parentOperators)
            deployedOperator
          }

        } else { // no reliability requirement
          for {
            hostInfo <- placementStrategy.findOptimalNode(context, cluster, Dependencies(parentOperators.toList, List()), HostInfo(cluster.selfMember, operator), operator)
            deployedOperator <- deployNode(mode, context, cluster, operator, createdCallback, eventCallback, hostInfo, false, None, parentOperators)
          } yield {
            hostInfoResult = hostInfo
            deployedOperator
          }
        }
      }

    deployment.onComplete {
      case Success(deployedOperator) =>

        if(hostInfoResult != null) {
          SpecialStats.debug(s"$this", s"deployed $deployedOperator on; ${hostInfoResult.member} " +
            s"; with hostInfo ${hostInfoResult.operatorMetrics} " +
            s"; parents: $parentOperators; path.name: ${parentOperators.head.path.name}")
          mapek.knowledge.addOperator(deployedOperator)
          try {
            GUIConnector.sendInitialOperator(hostInfoResult.member.address, placementStrategy, deployedOperator.path.name, s"$mode", parentOperators, hostInfoResult)
          } catch {
            case e: Throwable =>
              SpecialStats.debug(s"$this", s"failed to update gui with operator $operator, cause: \n $e")
          }
        }
      case Failure(exception) => SpecialStats.debug(s"$this", s"failed to deploy $operator, cause: \n $exception")
    }
    deployment
  }

  private def deployNode(mode: Mode, context: ActorContext, cluster: Cluster, query: Query, createdCallback: Option[CreatedCallback], eventCallback: Option[EventCallback], hostInfo: HostInfo, backupMode: Boolean, mainNode: Option[ActorRef], dependsOn: Seq[ActorRef]): Future[ActorRef] = {

    query match {

      case streamQuery: StreamQuery =>
        Future { NodeFactory.createStreamNode(mode, hostInfo, backupMode, mainNode, streamQuery, dependsOn.head, createdCallback, eventCallback, context) }

      case sequenceQuery: SequenceQuery =>
        Future { NodeFactory.createSequenceNode(mode, hostInfo, backupMode, mainNode, sequenceQuery, dependsOn, createdCallback, eventCallback, context) }

      case filterQuery: FilterQuery =>
        Future { NodeFactory.createFilterNode(mode, hostInfo, backupMode, mainNode, filterQuery, dependsOn.head, createdCallback, eventCallback, context) }

      case dropElemQuery: DropElemQuery =>
        Future { NodeFactory.createDropElementNode(mode, hostInfo, backupMode, mainNode, dropElemQuery, dependsOn.head, createdCallback, eventCallback, context) }

      case selfJoinQuery: SelfJoinQuery =>
        Future { NodeFactory.createSelfJoinNode(mode, hostInfo, backupMode, mainNode, selfJoinQuery, dependsOn.head, createdCallback, eventCallback, context) }

      case joinQuery: JoinQuery =>
        Future { NodeFactory.createJoinNode(mode, hostInfo, backupMode, mainNode, joinQuery, dependsOn.head, dependsOn.tail.head, createdCallback, eventCallback, context) }

      case conjunctionQuery: ConjunctionQuery =>
        Future { NodeFactory.createConjunctionNode(mode, hostInfo, backupMode, mainNode, conjunctionQuery, dependsOn.head, dependsOn.tail.head, createdCallback, eventCallback, context) }

      case disjunctionQuery: DisjunctionQuery =>
        Future { NodeFactory.createDisjunctionNode(mode, hostInfo, backupMode, mainNode, disjunctionQuery, dependsOn.head, dependsOn.tail.head, createdCallback, eventCallback, context) }

      case customQuery: CustomQuery =>
        Future { NodeFactory.createCustomNode(customQuery.operatorName, mode, hostInfo, backupMode, mainNode, customQuery, dependsOn.head, createdCallback, eventCallback, context) }

      case benchmarkQuery: BenchmarkQuery =>
        Future { NodeFactory.createBenchmarkNode(mode, hostInfo, backupMode, mainNode, benchmarkQuery, dependsOn.head, createdCallback, eventCallback, context) }

      case kMeansQuery: KMeansQuery =>
        Future { NodeFactory.createKMeansNode(mode, hostInfo, backupMode, mainNode, kMeansQuery, dependsOn.head, createdCallback, eventCallback, context) }

      case forwardQuery: ForwardQuery =>
        Future { NodeFactory.createForwardNode(mode, hostInfo, backupMode, mainNode, forwardQuery, dependsOn.head, createdCallback, eventCallback, context) }
    }
  }

  def addDemand(demand: Seq[Requirement]): Unit = {
    log.info("Requirements changed. Notifying Monitor")
    mapek.monitor ! AddNewDemand(demand)
  }

  def removeDemand(demand: Requirement): Unit = {
    log.info("Requirements changed. Notifying Monitor")
    mapek.monitor ! RemoveDemand(demand)
  }

  def manualTransition(algorithmName: String): Unit = {
    log.info(s"Manual transition request to algorithm $algorithmName")
    mapek.planner ! ManualTransition(algorithmName)
  }

  def createAndStart(clientMonitors: Array[MonitorFactory])(eventCallback: Option[EventCallback]): ActorRef = {
    log.info("Creating and starting new QueryGraph");
    val root = create()(eventCallback)
    mapek.knowledge.addOperator(root)
    log.info(s"deployment complete, root actorRef: $root \n mapek.knowledge.operators: ${mapek.knowledge.getOperators().mkString("\n")}")
    mapek.knowledge notifyOperators StartExecution(mapek.knowledge.getStrategy().placement.strategyName())

    mapek.knowledge.client = this.context.actorOf(Props(
      classOf[ClientNode], root, clientMonitors, mapek),
      s"ClientNode-${UUID.randomUUID.toString}")
    log.info(s"started ${mapek.knowledge.getOperators().length} operators in mode ${mapek.knowledge.mode}")
    root
  }

  def create()(eventCallback: Option[EventCallback]): ActorRef = {
    mapek.knowledge.mode = getTransitionMode(query)
    log.info(s"" +
      s"executing in mode ${mapek.knowledge.mode}")
    startDeployment(
      query.asInstanceOf[Query],
      mapek.knowledge.mode,
      publishers,
      createdCallback,
      eventCallback,
      monitors)
  }

  def getTransitionMode(query: Query): Mode = {
    def frequencyReqDefined(query: Query): Boolean = {
      query.requirements.collect { case lr: FrequencyRequirement => lr }.nonEmpty
    }

    //TODO: use configuration
    def isSMS(q: Query): Boolean = q match {
      case leafQuery: LeafQuery => frequencyReqDefined(query)
      case unaryQuery: UnaryQuery => frequencyReqDefined(query) || isSMS(unaryQuery.sq)
      case binaryQuery: BinaryQuery => frequencyReqDefined(query) || isSMS(binaryQuery.sq1) || isSMS(binaryQuery.sq2)
    }
    if (isSMS(query)) Mode.SMS else Mode.MFGS
  }

  def stop(): Unit = {
    mapek.knowledge.client ! ShutDown()
  }

  def getPlacementStrategy(): PlacementStrategy = {
    placementStrategy
  }

  def getUpMembers(): SortedSet[Member] = {
    cluster.state.members.filter(x => x.status == MemberStatus.Up
      && x.hasRole("Candidate")
      && !x.address.equals(cluster.selfAddress)
    )
  }
}

//Closures are not serializable so callbacks would need to be wrapped in a class
abstract class CreatedCallback() {
  def apply(): Any
}

abstract class EventCallback() {
  def apply(event: Event): Any
}