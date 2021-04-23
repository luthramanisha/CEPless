package tcep.graph.nodes.traits

import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import akka.cluster.Cluster
import tcep.data.Events.Event
import tcep.graph.nodes.StreamNode
import tcep.graph.nodes.traits.Node.{Dependencies, UpdateTask}
import tcep.graph.transition._
import tcep.placement.benchmarking.PlacementAlgorithm
import tcep.placement.{HostInfo, OperatorMetrics}
import tcep.simulation.adaptive.cep.SystemLoad
import tcep.simulation.tcep.GUIConnector
import tcep.utils.{SizeEstimator, SpecialStats}

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.util.parsing.json.JSONObject

/**
  * Handling Cases of MFGS Transition
  **/
trait MFGSMode extends TransitionMode {
  override val modeName = "MFGS mode"

  val slidingMessageQueue = {
    ListBuffer[(ActorRef, Any)]()
  }

  override def preStart(): Unit = {
    super.preStart()
    messageQueueManager()
  }

  override def transitionReceive = this.mfgsReceive orElse super.transitionReceive

  private def mfgsReceive: PartialFunction[Any, Any] = {

    case TransferredState(algorithm, successor) => {
      log.info("parent successfully migrated to a new node")
      TransferredState(algorithm, successor) //childReceive will handle this message
    }

    case StartExecutionWithData(downtime, t, subs, data, algorithm) => {
      //sender() ! Ack()
      SpecialStats.log(this.getClass.getSimpleName, "Transitions", s"MFGS:TransitionTimeWithIperf ${System.currentTimeMillis() - t} millis")
      SpecialStats.log(this.getClass.getSimpleName, "Transitions", s"MFGS:TransitionTimeNoIperf ${System.currentTimeMillis() - downtime} millis")

      this.started = true
      this.subscribers ++= subs
      data.foreach(m => self.!(m._2)(m._1))
      executionStarted()
      self ! UpdateTask(algorithm)
      Unit //Nothing to forward for childReceive
    }

    case message: Event => {
      if(maxWindowTime() > 0) slidingMessageQueue += Tuple2(sender(), message)
      message //caching message and forwarding to the childReceive
    }

  }

  def messageQueueManager() = {
    if (maxWindowTime() != 0) {
      context.system.scheduler.schedule(
        FiniteDuration(maxWindowTime(), TimeUnit.SECONDS),
        FiniteDuration(maxWindowTime(), TimeUnit.SECONDS), () => {
          slidingMessageQueue.clear()
        }
      )
    }
  }

  override def executeTransition(requester: ActorRef, algorithm: PlacementAlgorithm): Unit = {

    SpecialStats.debug(s"${this}", s"MFGS transition: starting transition, requested by $requester")
    val startTime = System.currentTimeMillis()
    val cluster = Cluster(context.system)
    algorithm.placement.initialize(cluster)
    SpecialStats.debug(s"${this}", s"MFGS transition: $algorithm initialization complete")
    // Note: Stream node takes the most time to find the approrirate host, in future we might need to pin this
    // or findout the reason why its taking so long
    val parents = this match { // parents have transited already
      case u: UnaryNode => List(u.parentNode)
      case b: BinaryNode => List(b.parentNode1, b.parentNode2)
      case s: StreamNode => List(s.publisher)
      case _ => ???
    }
    val dependencies = Dependencies(parents, subscribers.toList)
    SpecialStats.debug(s"${this}", s"MFGS transition: looking for new host of ${this.hostInfo.operator} \n dependencies: $dependencies")
    // TODO reset placement metrics (bdp and msgOverhead) so displayed metrics are only for the transition placement, not including bdp or overhead from the initial one
    for {
      newHost <- algorithm.placement.findOptimalNode(this.context, cluster, dependencies, HostInfo(cluster.selfMember, hostInfo.operator, OperatorMetrics()), hostInfo.operator)
    } yield {

      if(newHost.member.equals(hostInfo.member)) { // keep msg overhead if operator does not move (otherwise 0 is displayed)
        newHost.operatorMetrics.accMsgOverhead = hostInfo.operatorMetrics.accMsgOverhead
      }

      val successor: ActorRef = createDuplicateNode(newHost)
      SpecialStats.debug(s"${this}", s"MFGS found new host $newHost, after ${System.currentTimeMillis()-startTime}ms created new operator: ${successor}")

      this.started = false
      val downTime = System.currentTimeMillis()

      log.info(s"asking for StartExecutionWithData")
      val startExecutionMessage = StartExecutionWithData(downTime, startTime, subscribers.toSet, slidingMessageQueue.toSet, algorithm.placement.strategyName())

      // TODO: create monitor to calculate transition msgOverhead_operator in terms of messages sent (in bytes)
      SpecialStats.log(s"$this", "Transitions", s"MFGS starting successor, message size: ${SizeEstimator.estimate(startExecutionMessage)} bytes")

      successor ! startExecutionMessage

      val migrationTime = System.currentTimeMillis() - downTime
      val nodeSelectionTime = System.currentTimeMillis() - startTime
      var parentList = new ListBuffer[JSONObject]()
      parents.foreach(parent => {
        parentList += JSONObject(Map(
          "operatorName" -> parent.path.name,
          "bandwidthDelayProduct" -> newHost.operatorMetrics.operatorToParentBDP.getOrElse(parent.path.name, Double.NaN),
          "messageOverhead" -> newHost.operatorMetrics.accMsgOverhead,
          "migrationTime" -> migrationTime,
          "nodeSelectionTime" -> nodeSelectionTime,
          "timestamp" -> System.currentTimeMillis()
        ))
      })


    // TODO: self.path.address is not there anymore, should be the old address of the operator (hostInfo.addr)
    GUIConnector.sendOperatorTransitionUpdate(hostInfo.member.address, self.path.name, newHost.member.address, algorithm, successor.path.name, migrationTime, parentList)

      requester ! TransferredState(algorithm, successor)
      SpecialStats.debug(s"${this}", s"MFGS transition step finished, notifying $requester and stopping self")
      SystemLoad.operatorRemoved()
      context.stop(self)
    }
  }

  def executionStarted()

}