package tcep.graph.nodes

import akka.actor.ActorRef
import customoperators.repository.RepositoryEvent
import customoperators.{CustomOperatorInterface, OperatorAddress}
import tcep.data.Events._
import tcep.data.Queries._
import tcep.graph.nodes.traits._
import tcep.graph.{CreatedCallback, EventCallback}
import tcep.placement.HostInfo
import tcep.graph.nodes.traits.Mode.Mode

import scala.concurrent.Future

case class CustomNode(var operatorName: String,
                      mode: Mode,
                      hostInfo: HostInfo,
                      backupMode: Boolean,
                      mainNode: Option[ActorRef],
                      query: UnaryQuery,
                      @volatile var parentNode: ActorRef,
                      createdCallback: Option[CreatedCallback],
                      eventCallback: Option[EventCallback]
                     )
  extends UnaryNode {

  var operatorAddress: OperatorAddress = _

  print(s"Initializing operator with name $operatorName")
  print("Calling CustomOperatorInterface")

  var operatorInterface = new CustomOperatorInterface(context = context)

  operatorInterface.requestOperator(this.operatorName, operatorAddress => {
    operatorInterface.addListener(operatorAddress, receiveOperatorEvent)
    this.operatorAddress = operatorAddress
    println("COI callback")
  })
  println("Finished init node")

  def sendEvent(event: Event): Unit = {
    if (operatorAddress == null) {
      println(s"Dropping event $event because operator was not yet registered!")
      return
    }
    operatorInterface.sendEvent(event, operatorAddress)
  }

  override def childNodeReceive: Receive = super.childNodeReceive orElse {
    case event: Event => sendEvent(event)
    case event: RepositoryEvent => receiveOperatorEvent(event.item)
    case unhandledMessage => log.info(s"unhandled message $unhandledMessage")
  }

  def receiveOperatorEvent(data: Any): Unit = {
    var list: List[String] = List()
    if (data.isInstanceOf[String]) {
      println(s"CustomNode: event received $data")
      val string = data.asInstanceOf[String]
      val parts = string.split(",")
      if (parts.length == 5) {
        emitEvent(Event5(parts(0), parts(1), parts(2), parts(3), parts(4)))
      } else {
        emitEvent(Event1(string))
      }
      return
    } else if (data.isInstanceOf[List[String]]) {
      list = data.asInstanceOf[List[String]]
    }
    print("PARSING EVENT")
    val event = list.length match {
      case 1 => Event1(list.head)
      case 2 => Event2(list.head, list(1))
      case 3 => Event3(list.head, list(1), list(2))
      case 4 => Event4(list.head, list(1), list(2), list(3))
      case 5 => Event5(list.head, list(1), list(2), list(3), list(4))
      case 6 => Event6(list.head, list(1), list(2), list(3), list(4), list(5))
    }
    emitEvent(event)
  }

  def createDuplicateNode(hostInfo: HostInfo): ActorRef = {
    val startTime = System.currentTimeMillis()
    val res = null // NodeFactory.createFilterNode(mode, hostInfo, backupMode, mainNode, query, parentNode, createdCallback, eventCallback, context)
    log.info(s"Spent ${System.currentTimeMillis() - startTime} milliseconds to initialize CustomNode")
    res
  }

  def maxWindowTime(): Int = 0
}

