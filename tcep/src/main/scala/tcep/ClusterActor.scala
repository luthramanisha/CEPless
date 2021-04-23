package tcep

import akka.actor.Actor
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.MemberEvent

/**
  * Created by mac on 09/08/2017.
  */
trait ClusterActor extends Actor {
  val cluster = Cluster(context.system)
  //implicit val ec = ExecutionContext.Implicits.global // TODO which to use?
  implicit val ec = context.dispatcher

  override def preStart(): Unit = {
    super.preStart()
    cluster.subscribe(self, classOf[MemberEvent])
  }

  override def postStop(): Unit = {
    super.postStop()
    cluster.unsubscribe(self)
  }
}
