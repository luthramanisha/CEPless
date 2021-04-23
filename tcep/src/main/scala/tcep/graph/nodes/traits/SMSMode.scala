package tcep.graph.nodes.traits

import akka.actor.ActorRef
import akka.cluster.Cluster
import tcep.data.Events.Event
import tcep.graph.nodes.StreamNode
import tcep.graph.nodes.traits.Node.Dependencies
import tcep.graph.transition.{StartExecutionWithDependencies, TransferredState}
import tcep.placement.benchmarking.PlacementAlgorithm
import tcep.placement.{HostInfo, OperatorMetrics}
import tcep.simulation.adaptive.cep.SystemLoad
import tcep.simulation.tcep.GUIConnector
import tcep.utils.{SizeEstimator, SpecialStats}

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.sys.process._
import scala.util.parsing.json.JSONObject


/**
  * Handling cases of SMS Transition
  **/
trait SMSMode extends TransitionMode {
  override val modeName = "SMS mode"

  //TODO: use the original approach mentioned in paper
  //TODO: divide the messages from parent nodes based on the sms
  protected val delta = 1000l
  protected val clockSyncTime = 500l //should be less than delta

  //Value will be emparically determined in real network

  override def transitionReceive = this.smsReceive orElse super.transitionReceive

  private def smsReceive: PartialFunction[Any, Any] = {

    case StartExecutionWithDependencies(subs, start, algorithm) => {
      //sender() ! Ack()

      log.info("Starting Execution With QueryDependencies")
      executionStarted() // was missing ?
      SpecialStats.log(this.getClass.getSimpleName, "Transitions", s"SMS:${System.currentTimeMillis() - start}")
      this.subscribers ++= subs
      started = true
      Unit
    }

    case message: Event if started => {
      message //forwarding to the childReceive
    }

  }

  override def executeTransition(requester: ActorRef, algorithm: PlacementAlgorithm): Unit = {

    log.info(s"executing transitions, requested by $requester")
    Future {
      val startTime = System.currentTimeMillis()
      val cluster = Cluster(context.system)
      algorithm.placement.initialize(cluster)
      val parents = this match {
        case u: UnaryNode => List(u.parentNode)
        case b: BinaryNode => List(b.parentNode1, b.parentNode2)
        case s: StreamNode => List(s.publisher)
        case _ => ???
      }
      val dependencies = Dependencies(parents, subscribers.toList)
      log.info(s"SMS transition: looking for new host of ${this.hostInfo.operator} \n dependencies: $dependencies")
      // TODO reset placement metrics (bdp and msgOverhead) so displayed metrics are only for the transition placement, not the initial one
      for {
        newHost <- algorithm.placement.findOptimalNode(this.context, cluster, dependencies, HostInfo(cluster.selfMember, hostInfo.operator, OperatorMetrics()), hostInfo.operator)
      } yield {
        if (newHost.member.equals(hostInfo.member)) { // keep msg overhead if operator does not move (otherwise 0 is displayed)
          newHost.operatorMetrics.accMsgOverhead = hostInfo.operatorMetrics.accMsgOverhead
        }
        val successor = createDuplicateNode(newHost)

        log.info(s"asking for StartExecutionWithDependencies")
        "ntpd -s" !

        //Thread.sleep(clockSyncTime)
        Thread.sleep(delta + maxWindowTime() * 1000)

        val startMigrationTime = System.currentTimeMillis()
        val startExecutionMessage = StartExecutionWithDependencies(subscribers.toList, startMigrationTime, algorithm.placement.strategyName())

        //TCEPUtils.ask(successor,startExecutionMessage)
        successor ! startExecutionMessage

        log.info(s"SMS PlacementOverhead: ${SizeEstimator.estimate(startExecutionMessage)}")
        this.started = false

        val nodeSelectionTime = System.currentTimeMillis() - startTime
        val migrationTime = System.currentTimeMillis() - startMigrationTime
        var parentList = new ListBuffer[JSONObject]()

        parents.foreach(parent => {
          parentList += JSONObject(Map(
            "operatorName" -> parent.path.name,
            "bandwidthDelayProduct" -> newHost.operatorMetrics.operatorToParentBDP.getOrElse(parent.path.name, Double.NaN),
            "messageOverhead" -> newHost.operatorMetrics.accMsgOverhead,
            "migrationTime" -> migrationTime,
            "nodeSelectionTime" -> nodeSelectionTime
          ))
        })

        log.info(s"updating requester about new successor ${successor.path.name}")
        GUIConnector.sendOperatorTransitionUpdate(hostInfo.member.address, self.path.name, newHost.member.address, algorithm, successor.path.name, migrationTime, parentList)
        requester ! TransferredState(algorithm, successor)
        log.info(s"${self.path.name} Killing Self")
        SystemLoad.operatorRemoved()
        this.context.stop(self)
      }
    }
  }
}