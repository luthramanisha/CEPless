package tcep.graph.nodes.traits

import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import akka.util.Timeout
import tcep.data.Events._
import tcep.data.Queries._
import tcep.graph.nodes.ShutDown
import tcep.graph.nodes.traits.Node.{OperatorMigrationNotice, Subscribe, UnSubscribe}
import tcep.graph.transition.{TransferredState, TransitionRequest}
import tcep.placement.benchmarking.PlacementAlgorithm
import tcep.utils.SpecialStats

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

/**
  * Handling of [[tcep.data.Queries.BinaryQuery]] is done by BinaryNode
  **/
trait BinaryNode extends Node {
  override val query: BinaryQuery

  @volatile var parentNode1: ActorRef
  @volatile var parentNode2: ActorRef

  private var parent1TransitInProgress = false
  private var parent2TransitInProgress = false
  private var transitionRequestor: ActorRef = _
  private var transitionStartTime: Long = _

  val p1List: ListBuffer[ActorRef] = ListBuffer(parentNode1)
  //TODO: watch members of the list to remove stopped actors
  val p2List: ListBuffer[ActorRef] = ListBuffer(parentNode2)

  var childNode1Created: Boolean = false
  var childNode2Created: Boolean = false


  implicit val resolveTimeout = Timeout(60, TimeUnit.SECONDS)

  override def executionStarted(): Unit = {
    parentNode1 ! Subscribe()
    parentNode2 ! Subscribe()
    log.info(s"subscribing for events from \n${parentNode1.toString()} and \n${parentNode2.toString()}")
  }

  def handleTransferredState(algorithm: PlacementAlgorithm, predecessor: ActorRef): X = {
    log.info(s"${self.path.name}:parent successfully migrated to the new node")

    transitionExecutionMode match {
      case TransitionExecutionModes.CONCURRENT_MODE => {

        if (p1List.contains(sender())) {
          p1List += predecessor
          parentNode1 = predecessor
          parent1TransitInProgress = false
        } else if (p2List.contains(sender())) {
          p2List += predecessor
          parentNode2 = predecessor
          parent2TransitInProgress = false
        } else {
          log.error("unexpected state of the system")
        }
      }

      case TransitionExecutionModes.SEQUENTIAL_MODE => {
        if (p1List.contains(sender())) {
          p1List += predecessor
          parent1TransitInProgress = false
          parentNode1 = predecessor
          p2List.last ! TransitionRequest(algorithm)
          parent2TransitInProgress = true

        }else if(p2List.contains(sender())){
          p2List += predecessor
          parent2TransitInProgress = false
          parentNode2 = predecessor
        }
      }
    }

    if (!parent1TransitInProgress && !parent2TransitInProgress) {
      log.info(s"parents transition complete, executing own transition -\n new parents: ${parentNode1.toString()} ${parentNode2.toString()}")
      SpecialStats.log(this.getClass.getName, "Transitions", s"WaitTime:${System.currentTimeMillis() - transitionStartTime}")
      executeTransition(transitionRequestor, algorithm)
    }
  }

  override def childNodeReceive: Receive = {
    case DependenciesRequest => sender ! DependenciesResponse(Seq(p1List.last, p2List.last))
    case Created if p1List.contains(sender()) => {
      childNode1Created = true
      if (childNode2Created) emitCreated()
    }
    case Created if p2List.contains(sender()) => {
      childNode2Created = true
      if (childNode1Created) emitCreated()
    }

    case event: Event if !p1List.contains(sender()) && !p2List.contains(sender()) => {
      log.debug(s"${self.path.name}: ParentNodes ${p1List.map(n => n.path)} ${p2List.map(n => n.path)} " +
        s"\nReceived event $event from: ${sender().path}")
    }

    //parent has transited to the new node
    case TransferredState(algorithm, predecessor) => {
      handleTransferredState(algorithm, predecessor)
    }

    case OperatorMigrationNotice(oldOperator, newOperator) => { // received from migrating parent
      if(p1List.contains(oldOperator)) {
        p1List -= oldOperator
        p1List += newOperator
        parentNode1 = newOperator
      }
      if(p2List.contains(oldOperator)) {
        p2List -= oldOperator
        p2List += newOperator
        parentNode2 = newOperator
      }
      newOperator ! Subscribe()
      log.info(s"received operator migration notice from ${oldOperator}, \n new operator is $newOperator \n updated parents $p1List $p2List")
    }

    case ShutDown() => {
      p1List.last ! ShutDown()
      p2List.last ! ShutDown()
      context.stop(self)
    }
  }

  override def handleTransitionRequest(requester: ActorRef, algorithm: PlacementAlgorithm) = {
    transitionInitiated = true
    transitionStartTime = System.currentTimeMillis()
    transitionRequestor = requester

    transitionExecutionMode match {
      case TransitionExecutionModes.CONCURRENT_MODE => {

        p1List.last ! TransitionRequest(algorithm)
        p2List.last ! TransitionRequest(algorithm)

        parent1TransitInProgress = true
        parent2TransitInProgress = true
      }
      case TransitionExecutionModes.SEQUENTIAL_MODE => {
        p1List.last ! TransitionRequest(algorithm)

        parent1TransitInProgress = true
        parent2TransitInProgress = false
      }

      case default => {
        throw new Error("Invalid Configuration. Stopping execution of program")
      }
    }

  }

  def transitionRequestInConcurrentMode(requester: ActorRef, algorithm: PlacementAlgorithm): Unit = {
    log.info(s"Asking ${parentNode1.path.name} and ${parentNode2.path.name} to transit to new algorithm ${algorithm.placement.getClass}")
    ???
  }

  def transitionRequestInSeqMode(requester: ActorRef, algorithm: PlacementAlgorithm): Unit = {
    Future {
      log.info(s"Asking ${parentNode1.path.name} and ${parentNode2.path.name} to transit to new algorithm ${algorithm.placement.getClass}")
      transitionStartTime = System.currentTimeMillis()
      transitionRequestor = requester

      p1List.last ! TransitionRequest(algorithm)
      p2List.last ! TransitionRequest(algorithm)
    }
  }


  override def postStop(): X = {
    super.postStop()
    log.info(s"stopping self on ${this.hostInfo.member.address.host}, unsubscribing from " +
      s"\n${p1List.last} : ${p1List.last.path.address} and" +
      s"\n${p2List.last} : ${p2List.last.path.address}")
    p1List.last ! UnSubscribe()
    p2List.last ! UnSubscribe()
  }

  def getParentNodes: Seq[ActorRef] = Seq(p1List.last, p2List.last)

}
