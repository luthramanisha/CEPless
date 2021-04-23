package tcep.utils

import java.util.concurrent.{Executors, TimeUnit}

import akka.actor.{ActorRef, ActorSelection, Address, RootActorPath}
import akka.cluster.{Cluster, Member}
import akka.pattern.{Patterns, ask}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.discovery.vivaldi.{Coordinates, DistVivaldiActor}
import org.slf4j.LoggerFactory
import tcep.machinenodes.helper.actors._

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}


/**
  * Created by raheel on 18/01/2018.
  * Contains generic utility methods
  */
object TCEPUtils {

  val log = LoggerFactory.getLogger(getClass)
  implicit val timeout = Timeout(ConfigFactory.load().getInt("constants.default-request-timeout"), TimeUnit.SECONDS)
  //private implicit val ec: ExecutionContextExecutor = ExecutionContext.fromExecutor(new scala.concurrent.forkjoin.ForkJoinPool)
  private val pool = Executors.newCachedThreadPool()
  private implicit val ec = ExecutionContext.fromExecutorService(pool)

  def executeAndLogTime[R](block: => R, tag: String): R = {
    val t0 = System.currentTimeMillis()
    val result = block // call-by-name
    val t1 = System.currentTimeMillis()
    SpecialStats.log(this.getClass.getSimpleName, tag, s"${t1 - t0}")
    result
  }

  /**
    * get the coordinates cached by the local DistVivaldiActor
    * @param cluster
    * @param node
    * @return
    */
  def getCoordinatesOfNode(cluster: Cluster, node: Address): Future[Coordinates] = {

    if(cluster.selfAddress.equals(node)) {
      Future { DistVivaldiActor.localPos.coordinates }
    } else {

      val ask: Future[Coordinates] = for {
        localVivaldiActor <- selectDistVivaldiOn(cluster, cluster.selfAddress).resolveOne()
        coordResponse <- Patterns.ask(localVivaldiActor, CoordinatesRequest(node), timeout).mapTo[CoordinatesResponse]
      } yield coordResponse.coordinate
      ask.onComplete {
        case Success(response) =>
        case Failure(exception) =>
          log.error(s"error while retrieving coordinates of $node from local vivaldiActor: \n ${exception.toString}")
          throw exception
      }
      ask
    }
  }

  def getLoadOfMember(cluster: Cluster, node: Member): Future[Double] = askNode(cluster, node, LoadRequest()).asInstanceOf[Future[Double]]

  def getBandwidthBetweenNodes(cluster: Cluster, source: Member, target: Member): Future[Double] = {

    if(source.equals(target)) return Future { 0.0d }
    val sourceTaskManagerActorRequest: Future[ActorRef] = this.getTaskManagerOfMember(cluster, source)
    val result = for {
      sourceTaskManager <- sourceTaskManagerActorRequest
      ask <- Patterns.ask(sourceTaskManager, SingleBandwidthRequest(target), timeout).mapTo[SingleBandwidthResponse]
    } yield ask.bandwidth
    result
    //log.error(s"unable to contact ${source} while determining bandwidth to $target, using default")
    //ConfigFactory.load().getDouble("constants.default-data-rate")
  }

  /**
    * retrieves all bandwidth measurements a node has to other Members
    */
  def getAllBandwidthsFromNode(cluster: Cluster, node: Member): Future[Map[Member, Double]] = {
    log.debug(s"asking node $node for all its bandwidth measurements")
    val taskManagerActorRequest: Future[ActorRef] = this.getTaskManagerOfMember(cluster, node)
    for {
      taskManager <- taskManagerActorRequest
      allBandwidthsResponse <- (taskManager ? AllBandwidthsRequest()).mapTo[AllBandwidthsResponse]
    } yield allBandwidthsResponse.bandwidthMap
  }

  def getBDPBetweenNodes(cluster: Cluster, source: Member, target: Member): Future[Double] = {

    if(source.equals(target)) return Future { 0.0d }
    val bdp = for {
      latency <- this.getVivaldiDistance(cluster, source, target)
      bw <- this.getBandwidthBetweenNodes(cluster, source, target).map(bw => bw / 1000.0d) // Mbit/s -> Mbit/ms since vivaldi distance is ~latency in ms
    } yield {
      if(latency <= 0.0d || bw <= 0.0d) log.warn(s"BDP between non-equal nodes $source and $target is zero!")
      latency * bw
    }
    bdp
  }

  /**
    * @return the latency between source and target as approximated by their vivaldi actor coordinate distance
    */
  def getVivaldiDistance(cluster: Cluster, source: Member, target: Member): Future[Double] = {
    val sourceCoordRequest: Future[Coordinates] = getCoordinatesOfNode(cluster, source.address)
    val targetCoordRequest: Future[Coordinates] = getCoordinatesOfNode(cluster, target.address)
    val result = for {
      sourceCoords <- sourceCoordRequest
      targetCoords <- targetCoordRequest
    } yield sourceCoords.distance(targetCoords) // both requests must complete before this future can complete
    result
  }

  def getLatencyBetween(cluster: Cluster, source: Member, target: Member): Future[Double] = getVivaldiDistance(cluster, source, target)
  /*
  // be aware that this ask blocks the current thread! (Await.result)
  def askNodeForOption(cluster: Cluster, node: Member, request: Any): Option[Any] = {

    SpecialStats.debug(s"$this", s"asking $request of $node")
    try {
      val taskManagerActor = getTaskManagerOfMember(cluster, node)
      if (taskManagerActor.isDefined) {
        val ask = Patterns.ask(taskManagerActor.get, request, timeout)
        val response = Await.result(ask, 15.seconds) // blocking
        SpecialStats.debug(s"$this", s"received response for $request from $node")
        Some(response)
      } else {
        log.error(s"unable to contact TaskManager on ${node.address} before sending ${request}")
        None
      }
    } catch {
      case e: Throwable =>
        log.error(s"unable to get a response to request $request from ${node}, \n cause: $e")
        None
    }
  }
  */
  // non-blocking ask
  def askNode(cluster: Cluster, node: Member, request: Any): Future[AnyRef] = {

    val taskManager: ActorSelection = selectTaskManagerOn(cluster, node.address)
    SpecialStats.debug(s"$this", s"asking $request of $taskManager")
    Patterns.ask(taskManager, request, timeout)
  }

  def getTaskManagerOfMember(cluster: Cluster, node: Member): Future[ActorRef] = {
    if(node.address == null) throw new RuntimeException(s"received member without address: ${node}")
    val actorSelection: ActorSelection = selectTaskManagerOn(cluster, node.address)
    val actorResolution: Future[ActorRef] = actorSelection.resolveOne()(timeout)
    actorResolution
  }

  def selectTaskManagerOn(cluster: Cluster, address: Address): ActorSelection = cluster.system.actorSelection(RootActorPath(address) / "user" / "TaskManager*")
  def selectDistVivaldiOn(cluster: Cluster, address: Address): ActorSelection = cluster.system.actorSelection(RootActorPath(address) / "user" / "DistVivaldiRef*")
  def selectPublisherOn(cluster: Cluster, address: Address): ActorSelection = cluster.system.actorSelection(RootActorPath(address) / "user" / "P:*")
  def selectSimulator(cluster: Cluster): ActorSelection = {
    val simulatorNode = cluster.state.members.find(_.hasRole("Subscriber")).getOrElse(throw new RuntimeException("could not find Subscriber node in cluster!"))
    cluster.system.actorSelection(RootActorPath(simulatorNode.address) / "user" / "SimulationSetup*")
  }
  def getAddrStringFromRef(ref: ActorRef): String = ref.path.address.toString
  def getAddrFromRef(ref: ActorRef): Address = ref.path.address
  def getMemberFromActorRef(cluster: Cluster, ref: ActorRef): Member =
    cluster.state.members.find(m => m.address.toString == ref.path.address.toString).getOrElse({
      log.warn(s"could not find member of actor $ref, returning self ${cluster.selfMember}")
      cluster.selfMember})
  def getPublisherHosts(cluster: Cluster): Map[String, Member] = {
    cluster.state.members.filter(_.hasRole("Publisher"))
      .map(p => s"P:${p.address.host.getOrElse("UnknownHost")}:${p.address.port.getOrElse(0)}" -> p).toMap
  }

  /**
    * finds all publisher actors in the cluster; blocks until all are identified!
    * (only used in PlacementStrategy initialization)
    */
  def getPublisherActors(cluster: Cluster): Map[String, ActorRef] = {
    val publisherHosts = getPublisherHosts(cluster)
    // in this implementation, each publisher member (PublisherApp) runs at most one publisher!
    Await.result(makeMapFuture(publisherHosts.map(m => m._1 -> selectPublisherOn(cluster, m._2.address).resolveOne() )), timeout.duration)
  }

  /**
    * @param futureValueMap map with future values
    * @return a future that completes once all the value futures have completed
    */
  def makeMapFuture[K,V](futureValueMap: Map[K, Future[V]]): Future[Map[K,V]] =
    Future.traverse(futureValueMap) { case (k, fv) => fv.map(k -> _) } map(_.toMap)

}