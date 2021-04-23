package tcep.graph.nodes

import java.util.concurrent.TimeUnit

import akka.actor.{ActorLogging, ActorRef}
import akka.util.Timeout
import com.espertech.esper.client._
import tcep.data.Events._
import tcep.data.Queries._
import tcep.factories.NodeFactory
import tcep.graph.nodes.traits.Mode._
import tcep.graph.nodes.traits.Node.{Subscribe, UnSubscribe}
import tcep.graph.nodes.traits._
import tcep.graph.{CreatedCallback, EventCallback, QueryGraph}
import tcep.placement.HostInfo
import tcep.publishers.Publisher._

/**
  * Handling of [[tcep.data.Queries.SequenceQuery]] is done by SequenceNode.
  *
  * @see [[QueryGraph]]
  **/

case class SequenceNode(mode: Mode,
                        hostInfo: HostInfo,
                        backupMode: Boolean,
                        mainNode: Option[ActorRef],
                        query: SequenceQuery,
                        publishers: Seq[ActorRef],
                        createdCallback: Option[CreatedCallback],
                        eventCallback: Option[EventCallback])

  extends LeafNode with EsperEngine with ActorLogging {

  override val esperServiceProviderUri: String = name

  implicit val resolveTimeout = Timeout(60, TimeUnit.SECONDS)

  var subscription1Acknowledged: Boolean = false
  var subscription2Acknowledged: Boolean = false

  override def executionStarted(): X = {
    log.info(s"subscribing for events from $publishers")

    publishers.foreach(_ ! Subscribe())

    addEventType("sq1", SequenceNode.createArrayOfNames(query.s1), SequenceNode.createArrayOfClasses(query.s1))
    addEventType("sq2", SequenceNode.createArrayOfNames(query.s2), SequenceNode.createArrayOfClasses(query.s2))

    val epStatement: EPStatement = createEpStatement("select * from pattern [every (sq1=sq1 -> sq2=sq2)]")

    val updateListener: UpdateListener = (newEventBeans: Array[EventBean], _) => newEventBeans.foreach(eventBean => {
      val values: Array[Any] =
        eventBean.get("sq1").asInstanceOf[Array[Any]] ++
          eventBean.get("sq2").asInstanceOf[Array[Any]]
      val event: Event = values.length match {
        case 2 => Event2(values(0), values(1))
        case 3 => Event3(values(0), values(1), values(2))
        case 4 => Event4(values(0), values(1), values(2), values(3))
        case 5 => Event5(values(0), values(1), values(2), values(3), values(4))
        case 6 => Event6(values(0), values(1), values(2), values(3), values(4), values(5))
      }
      emitEvent(event)
    })

    epStatement.addListener(updateListener)
  }

  override def childNodeReceive: Receive = super.childNodeReceive orElse {
    case DependenciesRequest =>
      sender ! DependenciesResponse(Seq.empty)
    case AcknowledgeSubscription if sender().equals(publishers.head) =>
      subscription1Acknowledged = true
      if (subscription2Acknowledged) emitCreated()
    case AcknowledgeSubscription if sender().equals(publishers(1)) =>
      subscription2Acknowledged = true
      if (subscription1Acknowledged) emitCreated()
    case event: Event if sender().equals(publishers.head) => event match {
      case Event1(e1) => sendEvent("sq1", Array(toAnyRef(e1)))
      case Event2(e1, e2) => sendEvent("sq1", Array(toAnyRef(e1), toAnyRef(e2)))
      case Event3(e1, e2, e3) => sendEvent("sq1", Array(toAnyRef(e1), toAnyRef(e2), toAnyRef(e3)))
      case Event4(e1, e2, e3, e4) => sendEvent("sq1", Array(toAnyRef(e1), toAnyRef(e2), toAnyRef(e3), toAnyRef(e4)))
      case Event5(e1, e2, e3, e4, e5) => sendEvent("sq1", Array(toAnyRef(e1), toAnyRef(e2), toAnyRef(e3), toAnyRef(e4), toAnyRef(e5)))
      case Event6(e1, e2, e3, e4, e5, e6) => sendEvent("sq1", Array(toAnyRef(e1), toAnyRef(e2), toAnyRef(e3), toAnyRef(e4), toAnyRef(e5), toAnyRef(e6)))
    }
    case event: Event if sender().equals(publishers(1)) => event match {
      case Event1(e1) => sendEvent("sq2", Array(toAnyRef(e1)))
      case Event2(e1, e2) => sendEvent("sq2", Array(toAnyRef(e1), toAnyRef(e2)))
      case Event3(e1, e2, e3) => sendEvent("sq2", Array(toAnyRef(e1), toAnyRef(e2), toAnyRef(e3)))
      case Event4(e1, e2, e3, e4) => sendEvent("sq2", Array(toAnyRef(e1), toAnyRef(e2), toAnyRef(e3), toAnyRef(e4)))
      case Event5(e1, e2, e3, e4, e5) => sendEvent("sq2", Array(toAnyRef(e1), toAnyRef(e2), toAnyRef(e3), toAnyRef(e4), toAnyRef(e5)))
      case Event6(e1, e2, e3, e4, e5, e6) => sendEvent("sq2", Array(toAnyRef(e1), toAnyRef(e2), toAnyRef(e3), toAnyRef(e4), toAnyRef(e5), toAnyRef(e6)))
    }
    case unhandledMessage => log.info(s"unhandled message $unhandledMessage")
  }


  def createDuplicateNode(hostInfo: HostInfo): ActorRef = {
    NodeFactory.createSequenceNode(mode, hostInfo, backupMode, mainNode, query, publishers, createdCallback, eventCallback, context)
  }

  def getParentNodes: Seq[ActorRef] = publishers

  override def postStop(): X = {
    super.postStop()
    destroyServiceProvider()
    publishers.foreach(p => p ! UnSubscribe())
  }

  def maxWindowTime(): Int = 0
}

object SequenceNode {
  def createArrayOfNames(noReqStream: NStream): Array[String] = noReqStream match {
    case _: NStream1[_] => Array("e1")
    case _: NStream2[_, _] => Array("e1", "e2")
    case _: NStream3[_, _, _] => Array("e1", "e2", "e3")
    case _: NStream4[_, _, _, _] => Array("e1", "e2", "e3", "e4")
    case _: NStream5[_, _, _, _, _] => Array("e1", "e2", "e3", "e4", "e5")
  }

  def createArrayOfClasses(noReqStream: NStream): Array[Class[_]] = {
    val clazz: Class[_] = classOf[AnyRef]
    noReqStream match {
      case _: NStream1[_] => Array(clazz)
      case _: NStream2[_, _] => Array(clazz, clazz)
      case _: NStream3[_, _, _] => Array(clazz, clazz, clazz)
      case _: NStream4[_, _, _, _] => Array(clazz, clazz, clazz, clazz)
      case _: NStream5[_, _, _, _, _] => Array(clazz, clazz, clazz, clazz, clazz)
    }
  }
}
