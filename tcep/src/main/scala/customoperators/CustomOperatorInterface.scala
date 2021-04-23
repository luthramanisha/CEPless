package customoperators

import java.util.UUID

import akka.actor.{ActorContext, ActorRef, ActorSystem, Deploy, Props}
import customoperators.repository.{EventRepository, InfinispanRepository, RedisRepository}
import scalaj.http.{Http, HttpOptions}
import tcep.data.Events.{Event, Event1, Event2, Event3, Event4, Event5, Event6}

import scala.collection.mutable
import scala.util.Random
import scala.util.parsing.json.{JSON, JSONObject}

/**
  * Interface for communicating with the abstraction layer for custom operators
  */
class CustomOperatorInterface(context: ActorContext) extends EventHandler {

  private val listeners = mutable.Map[String, Any => Unit]()
  private val operators = mutable.Map[String, OperatorAddress]()

  var repository: EventRepository = null

  print("INIT COI")

  /**
    * Registers the current CEP system node at the NodeManager
    */
  def requestOperator(operatorName: String, cb: OperatorAddress => Unit): Unit = {
    print("Requesting Operator")
    val localhostname = java.net.InetAddress.getLocalHost.getHostName
    val opRequestIdentifier = localhostname + "-" + Random.alphanumeric.take(10).mkString + "-" + operatorName

    val data = Map(
      "name" -> operatorName,
      "requestIdentifier" -> opRequestIdentifier
    )

    val json = JSONObject(data).toString()
    print(s"Registering node at Node Manager: $data")

    val response = Http(s"http://nodeManager:25003/requestOperator")
      .postData(json)
      .header("content-type", "application/json")
      .header("Charset", "UTF-8")
      .option(HttpOptions.readTimeout(100000))
      .asString
    print(response)
    val result = JSON.parseFull(response.body)
    result match {
      case Some(map: Map[String, String]) => {
        val addrIn = map("addrIn")
        val addrOut = map("addrOut")
        println(s"Using addrIn $addrIn and addrOut $addrOut for operator request $opRequestIdentifier")
        val operatorAddress = OperatorAddress(addrIn, addrOut)
        operators.put(opRequestIdentifier, operatorAddress)
        cb(operatorAddress)
        if (repository == null) {
          repository = getRepository
        }
        repository.listen(operatorAddress.addrOut)
        // receiveResults(operatorAddress)
      }
      case other => {
        println("Unknown data structure: " + other)
        cb(null)
      }
    }
  }

  /**
    * Processing loop that receives events and notifies listeners
    */
  /*private def receiveResults(operatorAddress: OperatorAddress): Unit = {
     this.repository.send(operatorAddress.addrOut)
  }*/

  /**
    * Puts a new event into the queue
    * @param e - event to be put in the queue
    */
  def sendEvent(e: Event, operatorAddress: OperatorAddress): Unit = {
    if (operatorAddress.addrIn == null) {
      println("Node not registered yet")
    }
    var publishable: String = null
    e match {
      case Event1(e1) => publishable = e1.toString
      case Event2(e1, e2) => publishable = List(e1.toString, e2.toString).mkString(",")
      case Event3(e1, e2, e3) => publishable = List(e1.toString, e2.toString, e3.toString).mkString(",")
      case Event4(e1, e2, e3, e4) => publishable = List(e1.toString, e2.toString, e3.toString, e4.toString).mkString(",")
      case Event5(e1, e2, e3, e4, e5) => publishable = List(e1.toString, e2.toString, e3.toString, e4.toString, e5.toString).mkString(",")
      case Event6(e1, e2, e3, e4, e5, e6) => publishable = List(e1.toString, e2.toString, e3.toString, e4.toString, e5.toString, e6.toString).mkString(",")
    }
    if (publishable != null) {
      println("Sending event to repo")
      // repository ! Tuple2(operatorAddress.addrIn, publishable)
      if (repository == null) {
        repository = getRepository
      }
      repository.send(operatorAddress.addrIn, publishable)
    } else {
      println("Event was empty!")
    }
  }

  /**
    * Adds an event listener to the listeners list
    * Each listener gets notified as soon as an event is received from the queue
    *
    * @param operatorAddress - Address object of the operator that was deployed
    * @param callback - function to be called
    * @return boolean indicating if the listener was added
    */
  def addListener(operatorAddress: OperatorAddress, callback: Any => Unit): Boolean = {
      if (listeners.getOrElse(operatorAddress.addrOut, null) != null) {
        return false
      }
      listeners.put(operatorAddress.addrOut, callback)
      true
  }

  override def processElement(value: String): Unit = {
    listeners.foreach((listener) => {
      listener._2(value)
    })
  }

  private def getRepository: EventRepository = {
    val dbType = System.getenv("DB_TYPE")
    if (dbType == "infinispan") {
      val host = System.getenv("INFINISPAN_HOST")
      new InfinispanRepository(this, host, 11222)
      /*context.system.actorOf(Props(
        classOf[InfinispanRepository],
        context.self,
        host,
        11222
      ), s"CustomNode${UUID.randomUUID.toString}"
      )*/
    } else {
      val host = System.getenv("REDIS_HOST")
      new RedisRepository(this, host, 6379)
      /*context.system.actorOf(Props(
          classOf[RedisRepository],
          context.self,
          host,
          6379
        ), s"CustomNode${UUID.randomUUID.toString}"
      )*/
    }
  }
}

