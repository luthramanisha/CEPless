package tcep.graph.nodes

import akka.actor.{ActorLogging, ActorRef}
import com.typesafe.config.ConfigFactory
import org.discovery.vivaldi.DistVivaldiActor
import tcep.ClusterActor
import tcep.data.Events
import tcep.data.Events._
import tcep.graph.nodes.traits.Node.{OperatorMigrationNotice, Subscribe}
import tcep.graph.qos.MonitorFactory
import tcep.graph.transition.{MAPEK, TransferredState, TransitionRequest}
import tcep.machinenodes.helper.actors.PlacementMessage
import tcep.placement.{HostInfo, OperatorMetrics}
import tcep.simulation.tcep.GUIConnector
import tcep.utils.{SpecialStats, TCEPUtils}

import scala.concurrent.Future

/**
  * Receives the final output of the query
  *
  **/

class ClientNode(var rootOperator: ActorRef, monitorFactories: Array[MonitorFactory], mapek: MAPEK) extends ClusterActor with ActorLogging {

  val defaultBandwidth = ConfigFactory.load().getInt("constants.default-data-rate")
  val monitors = monitorFactories.map(f => f.createNodeMonitor)
  var hostInfo: HostInfo = _
  var transitionStartTime: Long = _   //Not in transition
  var guiBDPUpdateSent = false

  override def receive: Receive = {
    case TransitionRequest(algo) => {
      val strategy = algo.placement

      log.info(s"Transiting system to ${strategy.getClass}")
      SpecialStats.debug(s"$this", s"transiting to $strategy")
      mapek.knowledge.transitionStatus = 1

      transitionStartTime = System.currentTimeMillis()
      rootOperator ! TransitionRequest(algo)
    }

    case TransferredState(_, replacement) => {
      mapek.knowledge.transitionStatus = 0
      val timetaken = (System.currentTimeMillis() - transitionStartTime).toFloat / 1000.0
      log.info(s"replacing operator node reference: $rootOperator \n with replacement $replacement \n cluster: $cluster")
      replaceOperator(replacement)

      log.info(s"Transition Completed in $timetaken seconds")
      SpecialStats.debug(s"$this", s"transition completed after $timetaken seconds")
      SpecialStats.log("ClientNode", "Transitions", s"TransTransition Time $timetaken seconds")
      GUIConnector.sendTransitionTimeUpdate(timetaken)
    }

    case OperatorMigrationNotice(oldOperator, newOperator) => { // received from migrating parent
      this.replaceOperator(newOperator)
      log.info(s"received operator migration notice from $oldOperator, \n new operator is $newOperator")
    }

    case event: Event if sender().equals(rootOperator) => {
      Events.printEvent(event, log)
      Events.updateMonitoringData(log, event, hostInfo) // also updates hostInfo.operatorMetrics.accumulatedBDP
      if(!guiBDPUpdateSent) {
        GUIConnector.sendBDPUpdate(hostInfo.graphTotalBDP, DistVivaldiActor.getLatencyValues()) // this is the total BDP of the entire graph
        guiBDPUpdateSent = true
        SpecialStats.debug(s"$this", s"hostInfo after update: ${hostInfo.operatorMetrics}")
      }
      monitors.foreach(monitor => monitor.onEventEmit(event, mapek.knowledge.transitionStatus))
    }

    case event: Event if !sender().equals(rootOperator) => {
      log.info(s"unknown sender ${sender().path.name} my parent is ${rootOperator.path.name}")
    }

    case ShutDown() => {
      rootOperator ! ShutDown()
      log.info(s"Stopping self as received shut down message from ${sender().path.name}")
      context.stop(self)
    }

    case _ =>
  }

  private def replaceOperator(replacement: ActorRef) = {
    for {bdpToOperator: Double <- this.getBDPToOperator} yield {
      hostInfo = HostInfo(this.cluster.selfMember, null, OperatorMetrics(Map(rootOperator.path.name -> bdpToOperator))) // the client node does not have its own operator, hence null
      this.rootOperator = replacement
      replacement ! Subscribe()
      guiBDPUpdateSent = false // total (accumulated) bdp of the entire operator graph is updated when the first event arrives
    }
  }

  def getBDPToOperator: Future[Double] = {
    Future {
      defaultBandwidth
    }
    /*val rootOperatorMember = TCEPUtils.getMemberFromActorRef(cluster, rootOperator)
    def bdpRequest = TCEPUtils.getBDPBetweenNodes(cluster, cluster.selfMember, rootOperatorMember)
    bdpRequest recoverWith {
      case e: Throwable =>
        log.warning(s"failed to get BDP between clientnode and root operator, retrying once... \n cause: $e")
        bdpRequest
    } recoverWith { case _ => TCEPUtils.getVivaldiDistance(cluster, cluster.selfMember, rootOperatorMember) map { _ * defaultBandwidth }
    }*/
  }

  override def preStart(): Unit = {
    super.preStart()
    log.info(s"Subscribing for events from ${rootOperator.path.name}")
    rootOperator ! Subscribe()
    for { bdpToOperator <- this.getBDPToOperator } yield {
      hostInfo = HostInfo(this.cluster.selfMember, null, OperatorMetrics(Map(rootOperator.path.name -> bdpToOperator))) // the client node does not have its own operator, hence null
    }
  }

  override def postStop(): Unit = {
    super.postStop()
    log.info("Stopping self")
  }

}

case class ShutDown() extends PlacementMessage