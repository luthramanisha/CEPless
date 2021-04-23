package tcep.graph.nodes

import akka.actor.{ActorLogging, ActorRef}
import com.espertech.esper.client._
import tcep.data.Events._
import tcep.data.Queries._
import tcep.factories.NodeFactory
import tcep.graph.nodes.JoinNode._
import tcep.graph.nodes.traits.EsperEngine._
import tcep.graph.nodes.traits.Mode.{apply => _, _}
import tcep.graph.nodes.traits._
import tcep.graph.{CreatedCallback, EventCallback, QueryGraph}
import tcep.placement.HostInfo

/**
  * Handling of [[tcep.data.Queries.SelfJoinQuery]] is done by SelfJoinNode.
  *
  * @see [[QueryGraph]]
  **/

case class SelfJoinNode(mode: Mode,
                        hostInfo: HostInfo,
                        backupMode: Boolean,
                        mainNode: Option[ActorRef],
                        query: SelfJoinQuery,
                        @volatile var parentNode: ActorRef,
                        createdCallback: Option[CreatedCallback],
                        eventCallback: Option[EventCallback]

                       )
  extends UnaryNode with EsperEngine with ActorLogging {

  override val esperServiceProviderUri: String = name

  override def childNodeReceive: Receive = super.childNodeReceive orElse {
    case event: Event if parentsList.contains(sender()) => event match {
      case Event1(e1) => sendEvent("sq", Array(toAnyRef(e1)))
      case Event2(e1, e2) => sendEvent("sq", Array(toAnyRef(e1), toAnyRef(e2)))
      case Event3(e1, e2, e3) => sendEvent("sq", Array(toAnyRef(e1), toAnyRef(e2), toAnyRef(e3)))
      case Event4(e1, e2, e3, e4) => sendEvent("sq", Array(toAnyRef(e1), toAnyRef(e2), toAnyRef(e3), toAnyRef(e4)))
      case Event5(e1, e2, e3, e4, e5) => sendEvent("sq", Array(toAnyRef(e1), toAnyRef(e2), toAnyRef(e3), toAnyRef(e4), toAnyRef(e5)))
      case Event6(e1, e2, e3, e4, e5, e6) => sendEvent("sq", Array(toAnyRef(e1), toAnyRef(e2), toAnyRef(e3), toAnyRef(e4), toAnyRef(e5), toAnyRef(e6)))
    }

    case unhandledMessage => log.info(s"unhandled message $unhandledMessage")
  }

  override def createDuplicateNode(nodeInfo: HostInfo): ActorRef = {
    NodeFactory.createSelfJoinNode(mode, hostInfo, backupMode, mainNode, query, parentsList.last, createdCallback, eventCallback, context)
  }

  def maxWindowTime(): Int = {
    def windowTime(w: Window): Int = w match {
      case SlidingTime(seconds) => seconds
      case TumblingTime(seconds) => seconds
    }

    val w1 = windowTime(query.w1)
    val w2 = windowTime(query.w2)
    if (w1 > w2) w1 else w2
  }

  override def postStop(): Unit = {
    destroyServiceProvider()
  }

  override def preStart(): X = {
    super.preStart()

    addEventType("sq", createArrayOfNames(query.sq), createArrayOfClasses(query.sq))
    val epStatement: EPStatement = createEpStatement(
      s"select * from " +
        s"sq.${createWindowEplString(query.w1)} as lhs, " +
        s"sq.${createWindowEplString(query.w2)} as rhs")

    val updateListener: UpdateListener = (newEventBeans: Array[EventBean], _) => newEventBeans.foreach(eventBean => {
      val values: Array[Any] =
        eventBean.get("lhs").asInstanceOf[Array[Any]] ++ eventBean.get("rhs").asInstanceOf[Array[Any]]

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


}
