package tcep.graph.transition

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{Actor, ActorContext, ActorLogging, ActorRef, Cancellable, Props}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{MemberJoined, MemberLeft}
import org.slf4j.LoggerFactory
import tcep.ClusterActor
import tcep.data.Queries._
import tcep.graph.QueryGraph
import tcep.graph.nodes.traits.Mode
import tcep.graph.nodes.traits.Mode.Mode
import tcep.machinenodes.helper.actors.TransitionControlMessage
import tcep.placement.PlacementStrategy
import tcep.placement.benchmarking._
import tcep.placement.manets.StarksAlgorithm
import tcep.placement.sbon.PietzuchAlgorithm

import scala.collection.mutable
import scala.concurrent.duration._

/**
  * Created by raheel
  * on 02/10/2017.
  */

case class AddNewDemand(requirement: Seq[Requirement])

case class ChangeInNetwork(networkProperty: NetworkChurnRate)

case class ManualTransition(algorithmName: String)

case class RemoveDemand(requirement: Requirement*)

case class ScheduleTransition(strategy: PlacementStrategy)

class MAPEK(system: ActorContext, val query: Query, val queryGraph: QueryGraph) {
  val knowledge: KnowledgeComponent = new KnowledgeComponent(query)
  val monitor: ActorRef = system.actorOf(Props(new Monitor()))
  val analyzer: ActorRef = system.actorOf(Props(new Analyzer()))
  val planner: ActorRef = system.actorOf(Props(new Planner()))
  val executor: ActorRef = system.actorOf(Props(new Executor()))
  var benchmarkingNode: ActorRef = _

  def getBestPlacementStrategy(context: ActorContext, cluster: Cluster) = {
    val strategy: PlacementAlgorithm = BenchmarkingNode.selectBestPlacementAlgorithm(List.empty, knowledge.getDemands().toList)
    knowledge.setStrategy(strategy)
    strategy.placement
  }

  class Monitor extends ClusterActor {
    val log = LoggerFactory.getLogger(getClass)
    val churnRateThreshold = 3

    val demands = List[String]()
    var changeRate: AtomicInteger = new AtomicInteger(0)
    var updateStateScheduler: Cancellable = _
    var networkChurnRate: NetworkChurnRate = LowChurnRate //default value

    override def preStart(): Unit = {
      super.preStart()
      log.info("starting MAPEK Monitor")
      updateStateScheduler = this.context.system.scheduler.schedule(10 minutes, 5 minutes, this.self, UpdateState)
    }

    override def receive: Receive = {
      case MemberJoined(member) => {
        log.info(s"new member joined $member")
        changeRate.incrementAndGet()
      }

      case MemberLeft(member) => {
        changeRate.incrementAndGet()
      }

      case AddNewDemand(newDemands) => {
        log.info(s"received addRequirement $newDemands")
        saveDemand(newDemands)
        analyzer ! AddNewDemand(newDemands)
      }

      case RemoveDemand(demand) =>
        log.info(s"received removeRequirement $demand")
        removeDemand(demand)
      case UpdateState => updateState()

      case m => {
        log.info(s"ignoring unknown messages $m")
      }

      //TODO: monitor change in latency
      //ML: what is this about?
    }

    def saveDemand(newDemand: Seq[Requirement]) = {
      knowledge.addDemand(newDemand)
    }

    def removeDemand(demand: Requirement): Unit = {
      knowledge.removeDemand(demand)
    }

    def updateState(): Unit = {
      if (changeRate.intValue() >= churnRateThreshold && networkChurnRate.equals(LowChurnRate)) {
        analyzer ! ChangeInNetwork(HighChurnRate)
        log.info("high churn rate of the system, notifying analyzer")
        networkChurnRate = HighChurnRate
      } else if (changeRate.intValue() < churnRateThreshold && networkChurnRate.equals(HighChurnRate)) {
        log.info(s"low churn rate of the system, notifying analyzer")
        analyzer ! ChangeInNetwork(LowChurnRate)
        networkChurnRate = LowChurnRate
      }

      log.info(s"resetting churnrate ${changeRate.get()}")

      changeRate.set(0)
    }

    case object UpdateState

  }

  class Analyzer extends Actor {
    val log = LoggerFactory.getLogger(getClass)

    override def receive: Receive = {

      case AddNewDemand(newDemand) => {
        val currentRequirements = knowledge.getDemands()
        val currentAlgorithm = knowledge.getStrategy()
        if (currentRequirements.exists(req => !currentAlgorithm.containsDemand(req))) {
          log.info(s"requirement set $currentRequirements not supported by current placement algorithm $currentAlgorithm, forwarding to planner")
          planner ! AddNewDemand(newDemand)
        } else log.info(s"requirement set $currentRequirements is supported by current placement algorithm $currentAlgorithm, no change necessary")
      }

      case ChangeInNetwork(constraint) => {
        log.info(s"received ChangeInNetwork($constraint), sending to planner")
        planner ! ChangeInNetwork(constraint)
      }
    }
  }

  class Planner extends Actor with ActorLogging {
    override def receive: Receive = {
      case AddNewDemand(newDemand) => {
        val algorithm = BenchmarkingNode.selectBestPlacementAlgorithm(List.empty, knowledge.getDemands().toList)
        log.info(s"Got new Strategy from Benchmarking Node ${algorithm.placement.getClass}")
        executor ! algorithm
      }

      case ManualTransition(algorithmName) => {
        val algorithm = BenchmarkingNode.getAlgorithmByName(algorithmName)
        log.info(s"Manual transition to ${algorithm.placement.getClass}")
        executor ! algorithm
      }

      case ChangeInNetwork(constraint) => {
        log.info("applying transition due to change in environmental state")
        val algorithm = BenchmarkingNode.selectBestPlacementAlgorithm(constraint :: Nil, knowledge.getDemands().toList)
        log.info(s"Got new Strategy from Benchmarking Node ${algorithm.placement.getClass}")
        executor ! algorithm
      }
    }
  }

  class Executor extends Actor with ActorLogging {
    override def receive: Receive = {
      case PlacementAlgorithm(s, r, d, sr) => {
        knowledge.setStrategy(PlacementAlgorithm(s, r, d, sr))
        scheduleTransition(PlacementAlgorithm(s, r, d, sr))
      }
    }

    def scheduleTransition(placementAlgo: PlacementAlgorithm): Unit = {
      system.actorOf(Props(new TransitionManager())) ! TransitionRequest(placementAlgo)
    }
  }

  class TransitionManager extends Actor with ActorLogging {

    override def receive: Receive = {
      case TransitionRequest(algorithm) => {
        knowledge.transitionStarted()
        if (knowledge.mode == Mode.MFGS)
          mfgsTransition(query, algorithm)
        else
          smsTransition(algorithm)
      }
    }

    def mfgsTransition(query: Query, algorithm: PlacementAlgorithm) = {
      log.info("executing MFGS transition")
      knowledge.client ! TransitionRequest(algorithm)
    }

    def smsTransition(algorithm: PlacementAlgorithm) = {
      log.info("executing SMS transition")
      knowledge.client ! TransitionRequest(algorithm)
    }
  }

  class KnowledgeComponent(val query: Query) {

    private val log = LoggerFactory.getLogger(getClass)
    private val demands: scala.collection.mutable.Set[Requirement] = scala.collection.mutable.Set(pullDemands(query, List()).toSeq: _*)
    private val operators: mutable.Set[ActorRef] = mutable.Set()
    private var placementStrategy: PlacementAlgorithm = _

    private var transitionStartTime: Long = _
    private var totalTransitionTime: Long = _
    var transitionStatus: Int = 0 //0 Not in Transition, 1 in Transition

    var mode: Mode = _
    var client: ActorRef = _

    def notifyOperators(message: Any): Unit = {
      log.info(s"broadcasting message $message to \n ${operators.mkString("\n")}")
      operators.toList.reverse.foreach(opr => opr ! message)
    }

    def addOperator(x: ActorRef): Unit = operators += x

    def getDemands() = demands

    def addDemand(newRequirements: Seq[Requirement]): Unit = newRequirements.foreach(req => demands += req)

    def removeDemand(demand: Requirement): Unit = demands -= demand

    def getOperators() = operators.toList

    def duplicateOperators(): Int = placementStrategy.placement match {
      case StarksAlgorithm => 0
      case PietzuchAlgorithm => operators.size
    }

    def clearOperators() = operators.clear()

    def setStrategy(placementStrategy: PlacementAlgorithm) = this.placementStrategy = placementStrategy

    def getStrategy() = placementStrategy

    def hasReliabilityReq(): Boolean = (demands collect { case r: ReliabilityRequirement => r }).nonEmpty

    def transitionStarted() = {
      transitionStartTime = System.currentTimeMillis()
      transitionStatus = 1
    }

    def constantDataRateDemands(): Boolean = false

    def transitionEnded() = {
      totalTransitionTime = System.currentTimeMillis() - transitionStartTime
      transitionStatus = 0
    }

    def pullDemands(q: Query, accumulatedReq: List[Requirement]): Set[Requirement] = q match {
      case query: LeafQuery => q.requirements ++ accumulatedReq
      case query: UnaryQuery => q.requirements ++ pullDemands(query.sq, accumulatedReq)
      case query: BinaryQuery => {
        val child1 = pullDemands(query.sq1, q.requirements.toList ++ accumulatedReq)
        val child2 = pullDemands(query.sq2, q.requirements.toList ++ accumulatedReq)
        q.requirements ++ child1 ++ child2
      }
    }
  }

}

case class TransitionRequest(placementAlgo: PlacementAlgorithm) extends TransitionControlMessage
case class StopExecution() extends TransitionControlMessage
case class StartExecution(algorithmType: String) extends TransitionControlMessage
case class SaveStateAndStartExecution(state: List[Any]) extends TransitionControlMessage
case class StartExecutionWithData(downTime:Long, startTime: Long, subscribers: Set[ActorRef], data: Set[Tuple2[ActorRef, Any]], algorithmType: String) extends TransitionControlMessage
case class StartExecutionWithDependencies(subscribers: List[ActorRef], startTime: Long, algorithmType: String) extends TransitionControlMessage
case class TransferredState(placementAlgo: PlacementAlgorithm, replacement: ActorRef) extends TransitionControlMessage