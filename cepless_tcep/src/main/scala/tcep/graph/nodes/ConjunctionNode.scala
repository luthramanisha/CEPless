package tcep.graph.nodes

import akka.actor.ActorRef
import com.espertech.esper.client._
import tcep.data.Events._
import tcep.data.Queries._
import tcep.factories.NodeFactory
import tcep.graph.nodes.traits.EsperEngine._
import tcep.graph.nodes.traits._
import tcep.graph.{CreatedCallback, EventCallback, QueryGraph}
import tcep.placement.HostInfo

import scala.collection.mutable.ListBuffer

/**
  * Handling of [[tcep.data.Queries.ConjunctionQuery]] is done by ConjunctionNode.
  *
  * @see [[QueryGraph]]
  **/
case class ConjunctionNode(mode: Mode.Mode,
                           hostInfo: HostInfo,
                           backupMode: Boolean,
                           mainNode: Option[ActorRef],
                           query: ConjunctionQuery,
                           @volatile var parentNode1: ActorRef,
                           @volatile var parentNode2: ActorRef,
                           createdCallback: Option[CreatedCallback],
                           eventCallback: Option[EventCallback])
  extends BinaryNode with EsperEngine {

  override val esperServiceProviderUri: String = name

  var updateListener: UpdateListener = _

  override def preStart(): X = {
    super.preStart()


    addEventType("sq1", createArrayOfNames(query.sq1), createArrayOfClasses(query.sq1))
    addEventType("sq2", createArrayOfNames(query.sq2), createArrayOfClasses(query.sq2))

    updateListener = (newEventBeans: Array[EventBean], _) => newEventBeans.foreach(eventBean => {

      /**
        * @author Niels
        */
      val values1: Array[Any] = eventBean.get("sq1").asInstanceOf[Array[Any]]
      val values2: Array[Any] = eventBean.get("sq2").asInstanceOf[Array[Any]]
      val values = values1.tail ++ values2.tail

      val monitoringData1 = values1.head.asInstanceOf[ListBuffer[MonitoringData]]
      val monitoringData2 = values2.head.asInstanceOf[ListBuffer[MonitoringData]]

      val event: Event = values.length match {
        case 2 =>
          val res = Event2(values(0), values(1))
          mergeMonitoringData(res, monitoringData1, monitoringData2, log)
        case 3 =>
          val res = Event3(values(0), values(1), values(2))
          mergeMonitoringData(res, monitoringData1, monitoringData2, log)
        case 4 =>
          val res = Event4(values(0), values(1), values(2), values(3))
          mergeMonitoringData(res, monitoringData1, monitoringData2, log)
        case 5 =>
          val res = Event5(values(0), values(1), values(2), values(3), values(4))
          mergeMonitoringData(res, monitoringData1, monitoringData2, log)
        case 6 =>
          val res = Event6(values(0), values(1), values(2), values(3), values(4), values(5))
          mergeMonitoringData(res, monitoringData1, monitoringData2, log)
      }
      emitEvent(event)
    })

    val epStatement: EPStatement = createEpStatement("select * from pattern [every (sq1=sq1 and sq2=sq2)]")
    epStatement.addListener(updateListener)
  }

  override def childNodeReceive: Receive = super.childNodeReceive orElse {
    case event: Event if p1List.contains(sender()) => event match {
      case Event1(e1) => sendEvent("sq1", Array(toAnyRef(event.monitoringData), toAnyRef(e1)))
      case Event2(e1, e2) => sendEvent("sq1", Array(toAnyRef(event.monitoringData), toAnyRef(e1), toAnyRef(e2)))
      case Event3(e1, e2, e3) => sendEvent("sq1", Array(toAnyRef(event.monitoringData), toAnyRef(e1), toAnyRef(e2), toAnyRef(e3)))
      case Event4(e1, e2, e3, e4) => sendEvent("sq1", Array(toAnyRef(event.monitoringData), toAnyRef(e1), toAnyRef(e2), toAnyRef(e3), toAnyRef(e4)))
      case Event5(e1, e2, e3, e4, e5) => sendEvent("sq1", Array(toAnyRef(event.monitoringData), toAnyRef(e1), toAnyRef(e2), toAnyRef(e3), toAnyRef(e4), toAnyRef(e5)))
      case Event6(e1, e2, e3, e4, e5, e6) => sendEvent("sq1", Array(toAnyRef(event.monitoringData), toAnyRef(e1), toAnyRef(e2), toAnyRef(e3), toAnyRef(e4), toAnyRef(e5), toAnyRef(e6)))
    }
    case event: Event if p2List.contains(sender()) => event match {
      case Event1(e1) => sendEvent("sq2", Array(toAnyRef(event.monitoringData), toAnyRef(e1)))
      case Event2(e1, e2) => sendEvent("sq2", Array(toAnyRef(event.monitoringData), toAnyRef(e1), toAnyRef(e2)))
      case Event3(e1, e2, e3) => sendEvent("sq2", Array(toAnyRef(event.monitoringData), toAnyRef(e1), toAnyRef(e2), toAnyRef(e3)))
      case Event4(e1, e2, e3, e4) => sendEvent("sq2", Array(toAnyRef(event.monitoringData), toAnyRef(e1), toAnyRef(e2), toAnyRef(e3), toAnyRef(e4)))
      case Event5(e1, e2, e3, e4, e5) => sendEvent("sq2", Array(toAnyRef(event.monitoringData), toAnyRef(e1), toAnyRef(e2), toAnyRef(e3), toAnyRef(e4), toAnyRef(e5)))
      case Event6(e1, e2, e3, e4, e5, e6) => sendEvent("sq2", Array(toAnyRef(event.monitoringData), toAnyRef(e1), toAnyRef(e2), toAnyRef(e3), toAnyRef(e4), toAnyRef(e5), toAnyRef(e6)))
    }
    case unhandledMessage => log.info(s"unhandled message $unhandledMessage")
  }

  def createDuplicateNode(hostInfo: HostInfo): ActorRef = {
    NodeFactory.createConjunctionNode(mode, hostInfo, backupMode, mainNode, query, p1List.last, p2List.last, createdCallback,
      eventCallback, context)
  }

  override def postStop(): Unit = {
    destroyServiceProvider()
  }

  def maxWindowTime(): Int = 0
}
