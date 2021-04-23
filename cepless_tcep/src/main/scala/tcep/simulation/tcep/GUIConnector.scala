package tcep.simulation.tcep

import akka.actor.{ActorRef, Address}
import com.typesafe.config.ConfigFactory
import org.discovery.vivaldi.LatencyDistance
import org.slf4j.{Logger, LoggerFactory}
import scalaj.http.{Http, HttpOptions}
import tcep.graph.QueryGraph
import tcep.machinenodes.PublisherApp.{logger, options}
import tcep.placement.{HostInfo, PlacementStrategy}
import tcep.placement.benchmarking.PlacementAlgorithm

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.parsing.json.{JSONArray, JSONObject}


object GUIConnector {
  protected val log: Logger = LoggerFactory.getLogger(getClass)
  protected val guiEndpoint: String = ConfigFactory.load().getString("constants.gui-endpoint")

  /**
    * Sends the GUI server informationen of an operator transition
    *
    * @param oldAddress - Previous address of the operator
    * @param oldOperatorName - Previous name of the operator
    * @param transitionAddress - New address of the operator
    * @param algorithm - Placement algorithm used when placement got updated
    * @param operatorName - New name of the operator
    * @param migrationTime - Migration time of the transition
    * @param parents - Parent actor refs
    */
  def sendOperatorTransitionUpdate(oldAddress: Address, oldOperatorName: String, transitionAddress: Address, algorithm: PlacementAlgorithm, operatorName: String, migrationTime: Long, parents: ListBuffer[JSONObject]): Unit = {
    log.info(s"GUI update data $oldAddress $oldOperatorName")

    val data = Map(
      "oldMember" -> JSONObject(Map("host" -> oldAddress.host.getOrElse("empty"))),
      "member" -> JSONObject(Map("host" -> transitionAddress.host.getOrElse("empty"))),
      "oldOperator" -> JSONObject(Map("name" -> oldOperatorName)),
      "migrationTime" -> migrationTime,
      "operator" -> JSONObject(Map(
        "name" -> operatorName,
        "algorithm" -> algorithm.placement.strategyName(),
        "parents" -> JSONArray(parents.toList))
      )
    )

    val json = JSONObject(data).toString()
    // log.info(s"Sending GUI operator update $json to $guiEndpoint/setOperator")

    /*val result = Http(s"$guiEndpoint/setOperator")
      .postData(json)
      .header("content-type", "application/json")
      .header("Charset", "UTF-8")
      .option(HttpOptions.readTimeout(10000))
      .asString.code
    log.info(s"GUI update result code: $result")*/
  }

  /**
    * Send initial operator placement to GUI server
    * @param transitionAddress - address of the placed operator
    * @param algorithm - placement algorithm used for placing the operator
    * @param operatorName - name of the operator
    * @param transitionMode - transition mode that is used for transitions
    * @param parents - parent operators of the current operator
    * @param nodeInfo - node info of the node that the operator was placed on
    */
  def sendInitialOperator(transitionAddress: Address, algorithm: PlacementStrategy, operatorName: String, transitionMode: String, parents: Seq[ActorRef], nodeInfo: HostInfo): Unit = {
    var parentList = new ListBuffer[JSONObject]()

    parents.foreach(parent => {
      parentList += JSONObject(Map(
        "operatorName" -> parent.path.name,
        "bandwidthDelayProduct" -> nodeInfo.operatorMetrics.operatorToParentBDP.getOrElse(parent.path.name, Double.NaN),
        "messageOverhead" -> nodeInfo.operatorMetrics.accMsgOverhead,
        "timestamp" -> System.currentTimeMillis()
      ))
    })

    val data = Map(
      "member" -> JSONObject(Map("host" -> transitionAddress.host.getOrElse("empty"))),
      "operator" -> JSONObject(Map(
        "name" -> operatorName,
        "algorithm" -> algorithm.strategyName(),
        "parents" -> JSONArray(parentList.toList))
      ),
      "transitionMode" -> transitionMode
    )

    val json = JSONObject(data).toString()
    // log.info(s"Sending GUI operator update $json to $guiEndpoint/setOperator")

    /*val result = Http(s"$guiEndpoint/setOperator")
      .postData(json)
      .header("content-type", "application/json")
      .header("Charset", "UTF-8")
      .option(HttpOptions.readTimeout(10000))
      .asString.code
    log.info(s"GUI update result code: $result")*/
  }

  /**
    * Send initial publisher operator to the GUI server
    */
  def sendInitialPublisherOperator(): Unit = {
    val publisherName = options('name).toString
    val hostName = options('host).toString

    val data = Map(
      "member" -> JSONObject(Map("host" -> hostName)),
      "operator" -> JSONObject(Map(
        "name" -> s"P:$publisherName",
        "algorithm" -> ""
      ))
    )

    val json = JSONObject(data).toString()
    // logger.info(s"Sending GUI operator update $json to $guiEndpoint/setOperator")

    /*val result = Http(s"$guiEndpoint/setOperator")
      .postData(json)
      .header("content-type", "application/json")
      .header("Charset", "UTF-8")
      .option(HttpOptions.readTimeout(10000))
      .asString.code
    logger.info(s"GUI update result code: $result")*/
  }

  /**
    * Send the nodes that are in the cluster to the GUI server
    * @param queryGraph - query graph that contains the nodes to be send
    */
  def sendMembers(queryGraph: QueryGraph): Unit = {
    // log.info("Sending GUI update")
    val members = queryGraph.getUpMembers()
    val hostList = mutable.MutableList[JSONObject]()
    for (member <- members) {
      hostList += scala.util.parsing.json.JSONObject(Map("host" -> member.address.host.get))
    }
    hostList += JSONObject(Map("host" -> "node0 (Consumer)"))
    val data = scala.util.parsing.json.JSONObject(Map("members" -> scala.util.parsing.json.JSONArray(hostList.toList)))
    // log.info(s"Sending GUI update ${data.toString()} to $guiEndpoint/setMembers")
    /*val result = Http(s"$guiEndpoint/setMembers")
      .postData(data.toString())
      .header("content-type", "application/json")
      .header("Charset", "UTF-8")
      .option(HttpOptions.readTimeout(10000))
      .asString.code
    log.info(s"GUI update result code $result")*/
  }

  /**
    * Sends the overall Bandwidth Delay Product and vivaldi coordinates to the GUI server
    * @param bdp - the accumulated bandwidth delay product of the operator graph
    * @param latencyDistances - List of distances and vivaldi coordinates
    */
  def sendBDPUpdate(bdp: Double, latencyDistances: List[LatencyDistance]): Unit = {
    //log.info(s"GUI update consumer data BDP: $bdp")

    var coordinatesList = mutable.Map[String, JSONObject]()
    latencyDistances.foreach(obj => {
      coordinatesList += (obj.member1.host.get -> JSONObject(Map("x" -> obj.coord1.x, "y" -> obj.coord1.y, "h" -> obj.coord1.h)))
    })

    var latencyValues = ListBuffer[JSONObject]()
    latencyDistances.foreach(obj => {
      if (obj.member1.host.get != obj.member2.host.get) {
        latencyValues += JSONObject(Map(
          "source" -> obj.member1.host.get,
          "destination" -> obj.member2.host.get,
          "distance" -> obj.distance
        ))
      }
    })

    val data = Map(
      "bandwidthDelayProduct" -> bdp,
      "coordinates" -> JSONObject(coordinatesList.toMap),
      "latencyValues" -> JSONArray(latencyValues.toList)
    )

    val json = JSONObject(data).toString()
    // log.info(s"Sending GUI operator update to $guiEndpoint/consumer")

   /* val result = Http(s"$guiEndpoint/consumer")
      .postData(json)
      .header("content-type", "application/json")
      .header("Charset", "UTF-8")
      .option(HttpOptions.readTimeout(10000))
      .asString.code
    log.info(s"GUI update result code: $result")*/
  }

  /**
    * Sends the overall transition time to the GUI server
    * @param transitionTime - the accumulated transition time of the operator graph
    */
  def sendTransitionTimeUpdate(transitionTime: Double): Unit = {
    // log.info(s"GUI update data transition time $transitionTime")

    val data = Map(
      "time" -> transitionTime
    )

    val json = JSONObject(data).toString()
    // log.info(s"Sending GUI operator update $json to $guiEndpoint/setTransitionTime")

    /*val result = Http(s"$guiEndpoint/setTransitionTime")
      .postData(json)
      .header("content-type", "application/json")
      .header("Charset", "UTF-8")
      .option(HttpOptions.readTimeout(10000))
      .asString.code
    log.info(s"GUI update result code: $result")*/
  }
}
