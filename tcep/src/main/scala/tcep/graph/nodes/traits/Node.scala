package tcep.graph.nodes.traits

import java.util.concurrent.{Executors, ScheduledFuture, ScheduledThreadPoolExecutor, TimeUnit}

import akka.actor.{ActorLogging, ActorRef, Address}
import com.typesafe.config.ConfigFactory
import tcep.data.Events._
import tcep.data.Queries._
import tcep.graph.nodes.traits.Mode.Mode
import tcep.graph.nodes.traits.Node.{Dependencies, OperatorMigrationNotice, UpdateTask}
import tcep.graph.transition.{StartExecution, StartExecutionWithData, StartExecutionWithDependencies}
import tcep.graph.{CreatedCallback, EventCallback}
import tcep.machinenodes.helper.actors.PlacementMessage
import tcep.placement.{GlobalOptimalBDPAlgorithm, HostInfo, MobilityTolerantAlgorithm, RandomAlgorithm}
import tcep.placement.benchmarking.PlacementAlgorithm
import tcep.placement.manets.StarksAlgorithm
import tcep.placement.mop.RizouAlgorithm
import tcep.placement.sbon.PietzuchAlgorithm
import tcep.placement.vivaldi.VivaldiCoordinates
import tcep.simulation.adaptive.cep.SystemLoad
import tcep.simulation.tcep.GUIConnector

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.parsing.json.JSONObject

/**
  * The base class for all of the nodes
  **/
trait Node extends VivaldiCoordinates with MFGSMode with SMSMode with ActorLogging {

  val name: String = self.path.name
  val hostInfo: HostInfo
  val query: Query
  val mode: Mode
  @volatile var started: Boolean = false
  val placementUpdateInterval: Int = ConfigFactory.load().getInt("constants.placement.update-interval")
  private val pool = Executors.newCachedThreadPool()
  //protected implicit val ec = ExecutionContext.fromExecutorService(pool) // TODO check which executor type to use
  private val ex = new ScheduledThreadPoolExecutor(2)
  var transitionInitiated = false
  val backupMode: Boolean
  val mainNode: Option[ActorRef]
  val heartBeatInterval = 5

  val subscribers: mutable.Set[ActorRef] = mutable.Set[ActorRef]()
  var bdpToParents: mutable.Map[Address, Double] = mutable.Map()
  val createdCallback: Option[CreatedCallback]
  val eventCallback: Option[EventCallback]

  val transitionExecutionMode = ConfigFactory.load().getInt("constants.transition-execution-mode")
  var updateTask: ScheduledFuture[_] = _
  SystemLoad.newOperatorAdded()


  //TODO: do it in a separate thread
  def handleTransitionRequest(requester: ActorRef, algorithm: PlacementAlgorithm)

  override def executeTransition(requester: ActorRef, algorithm: PlacementAlgorithm) = {
    log.info(s"executing transition on $mode")
    val startTime = System.currentTimeMillis()
    if (mode == Mode.SMS) super[SMSMode].executeTransition(requester, algorithm)
    else super[MFGSMode].executeTransition(requester, algorithm)
    log.info(s"transition of $this took ${System.currentTimeMillis() - startTime}ms")

  }

  override def preStart(): X = {
    super.preStart()
    executionStarted()
    scheduleHeartBeat()
    log.debug(s"prestarting with mode $mode")
  }

  def scheduleHeartBeat() = {
    if (backupMode) {
      started = false
      this.context.watch(mainNode.get)
    }
  }

  def childNodeReceive: Receive

  override def receive: Receive =
    super[VivaldiCoordinates].receive orElse placementReceive orElse (
      if (mode == Mode.SMS)
        super[SMSMode].transitionReceive
      else
        super[MFGSMode].transitionReceive
      ) andThen childNodeReceive

  def placementReceive: Receive = {

    case StartExecution(algorithm) => {
      started = true
      executionStarted()
      self ! UpdateTask(algorithm)
      log.info(s"started operator $this $started")
    }
    // placement update task that must be set/sent by the actor deploying this operator
    // 1. receive notification after initial deployment, setting up a task on each operator
    // 2. periodically execute placement algorithm locally, migrate to new node (see transition) if necessary
    // 3. notify dependencies of new location
    case UpdateTask(algorithmType: String) => {

      val algorithm = algorithmType match {
        case "Relaxation" => PietzuchAlgorithm
        case "Rizou" => RizouAlgorithm
        case "MDCEP" => StarksAlgorithm
        case "Random" => RandomAlgorithm
        case "ProducerConsumer" => MobilityTolerantAlgorithm
        case "GlobalOptimalBDPAlgorithm" => GlobalOptimalBDPAlgorithm
        case other: String => throw new NoSuchElementException(s"need to add algorithm type $other to updateTask!")
      }
      if (algorithm.hasPeriodicUpdate()) {

        val task: Runnable = () => {
          val startTime = System.currentTimeMillis()
          val dependencies = Dependencies(getParentNodes.toList, subscribers.toList)
          algorithm.initialize(cluster)
          for {
            optimalHost: HostInfo <- algorithm.findOptimalNode(this.context, this.cluster, dependencies, HostInfo(cluster.selfMember, hostInfo.operator), hostInfo.operator)
          } yield {
            if (!cluster.selfAddress.equals(optimalHost.member.address)) {
              log.info(s"Relaxation periodic placement update for $query: " +
                s"\n moving operator from ${cluster.selfMember} to ${optimalHost.member} " +
                s"\n with dependencies $dependencies")
              // creates copy of operator on new host
              val newOperator: ActorRef = createDuplicateNode(optimalHost)

              this.started = false
              val downTime = System.currentTimeMillis()
              if (mode == Mode.MFGS) newOperator ! StartExecutionWithData(downTime, startTime, subscribers.toSet, slidingMessageQueue.toSet, algorithmType)
              else newOperator ! StartExecutionWithDependencies(subscribers.toList, System.currentTimeMillis(), algorithmType)
              newOperator ! UpdateTask(algorithmType)
              // inform subscribers about new operator location; parent will receive
              subscribers.foreach(_ ! OperatorMigrationNotice(self, newOperator))

              // taken from MFGSMode
              // TODO maybe make regular operator migrations distinguishable from transitions
              val migrationTime = System.currentTimeMillis() - downTime
              val nodeSelectionTime = System.currentTimeMillis() - startTime
              var parentList = new ListBuffer[JSONObject]()

            getParentNodes.foreach(parent => {
              parentList += JSONObject(Map(
                "operatorName" -> parent.path.name,
                "bandwidthDelayProduct" -> optimalHost.operatorMetrics.operatorToParentBDP.getOrElse(parent.path.name, Double.NaN),
                "messageOverhead" -> optimalHost.operatorMetrics.accMsgOverhead,
                "migrationTime" -> migrationTime,
                "nodeSelectionTime" -> nodeSelectionTime,
                "timestamp" -> System.currentTimeMillis()
              ))
            })
            // TODO: self.path.address is not there anymore, should be the old address of the operator (hostInfo.addr)
            GUIConnector.sendOperatorTransitionUpdate(hostInfo.member.address, self.path.name, optimalHost.member.address, PlacementAlgorithm(algorithm, List(), List(), 0), newOperator.path.name, migrationTime, parentList)

              log.info(s"${self} shutting down Self")
              SystemLoad.operatorRemoved()
              updateTask.cancel(true)
              context.stop(self)

            } else log.info(s"periodic placement update for $query: no change of host \n dependencies: ${dependencies}")
          }
        }

        updateTask = ex.scheduleAtFixedRate(task, placementUpdateInterval, placementUpdateInterval, TimeUnit.SECONDS)
        log.info(s"received placement update task, setting up periodic placement update every ${placementUpdateInterval}s")
      }
    }
  }

  def executionStarted()

  def emitCreated(): Unit = {
    if (createdCallback.isDefined) createdCallback.get.apply() else subscribers.foreach(_ ! Created)
  }

  def emitEvent(event: Event): Unit = {
    if (started) {
      //else discarding event
      updateMonitoringData(log, event, hostInfo)
      subscribers.foreach(s => {
        log.debug(s"\n${self.path.name} STREAMING EVENT $event FROM \n${sender.path.name} TO \n$subscribers")
        s ! event
      })
      if (eventCallback.isDefined) eventCallback.get.apply(event)
    } else {
      log.debug(s"discarding event! $event from ${sender.path.name} \n $getParentNodes")
    }
  }

  def toAnyRef(any: Any): AnyRef = {
    // Yep, an `AnyVal` can safely be cast to `AnyRef`:
    // https://stackoverflow.com/questions/25931611/why-anyval-can-be-converted-into-anyref-at-run-time-in-scala
    any.asInstanceOf[AnyRef]
  }

}

object Mode extends Enumeration {
  type Mode = Value
  val SMS, MFGS = Value
}

object Node {
  case class Subscribe() extends PlacementMessage
  case class UnSubscribe() extends PlacementMessage
  case class UpdateTask(algorithmType: String) extends PlacementMessage
  case class Dependencies(parents: List[ActorRef], subscribers: List[ActorRef])
  case class OperatorMigrationNotice(oldOperator: ActorRef, newOperator: ActorRef) extends PlacementMessage
}

object TransitionExecutionModes {
  val SEQUENTIAL_MODE = 0
  val CONCURRENT_MODE = 1
}