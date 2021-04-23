package tcep.graph.nodes.traits

import akka.actor.ActorRef
import tcep.data.Events._
import tcep.data.Queries._
import tcep.graph.nodes.ShutDown
import tcep.graph.nodes.traits.Node.{OperatorMigrationNotice, Subscribe, UnSubscribe}
import tcep.graph.transition.{TransferredState, TransitionRequest}
import tcep.graph.{CreatedCallback, EventCallback}
import tcep.placement.benchmarking.PlacementAlgorithm
import tcep.utils.SpecialStats

import scala.collection.mutable.ListBuffer

/**
  * Handling of [[tcep.data.Queries.UnaryQuery]] is done by UnaryNode
  **/
trait UnaryNode extends Node {

  override val query: UnaryQuery

  var parentNode: ActorRef //maintaining list because after migration, we could receive message from older parents

  val createdCallback: Option[CreatedCallback]
  val eventCallback: Option[EventCallback]
  private var transitionRequestor: ActorRef = _

  private var transitionStartTime: Long = _

  val parentsList: ListBuffer[ActorRef] = ListBuffer(parentNode) //can have multiple active unary parents due to SMS mode


  override def preStart(): X = {
    super.preStart()
  }

  override def executionStarted(): X = {
    log.info(s"subscribing for events from ${parentNode.path.name}, \n parentList: $parentsList")
    parentNode ! Subscribe()
  }

  override def childNodeReceive: Receive = {
    case event: Event if !parentsList.contains(sender()) => {
      log.info("Unhandled message in childNodeReceive")
      //log.info(s"ParentNodes: ${parentsList.toList.foreach(n => n.path)} ReceivedFrom: ${sender().path}")
    }

    case DependenciesRequest => sender() ! DependenciesResponse(Seq(parentsList.last))
    case Created if parentsList.contains(sender()) => emitCreated()

    case TransferredState(placementAlgo, successor) => {
      log.info("parent successfully migrated to the new node")

      if (parentsList.contains(sender())) {
        parentsList += successor
        parentNode = successor
        log.info(s"New parent ${successor.path.name}")
      } else {
        log.info("unexpected state of the system")
      }

      SpecialStats.log(this.getClass.getName,"Transitions",s"WaitTime:${System.currentTimeMillis() - transitionStartTime}")
      executeTransition(transitionRequestor, placementAlgo)
    }

    case OperatorMigrationNotice(oldOperator, newOperator) => { // received from migrating parent

      if(parentsList.contains(oldOperator)) {
        parentsList -= oldOperator
        parentsList += newOperator
        parentNode = newOperator
      }
      newOperator ! Subscribe()
      log.info(s"received operator migration notice from ${oldOperator}, \n new operator is $newOperator \n updated parent $parentsList")
    }

    case ShutDown() => {
      parentsList.last ! ShutDown()
      context.stop(self)
    }

  }

  override def handleTransitionRequest(requester: ActorRef, algorithm: PlacementAlgorithm) = {
    log.info(s"Asking ${parentsList.last.path.name} to transit to algorithm ${algorithm.placement.getClass}")
    transitionRequestor = requester
    transitionStartTime = System.currentTimeMillis()
    parentsList.last ! TransitionRequest(algorithm)
  }

  override def postStop(): X = {
    super.postStop()
    parentsList.last ! UnSubscribe()
  }

  override def getParentNodes: Seq[ActorRef] = Seq(parentsList.last)
}