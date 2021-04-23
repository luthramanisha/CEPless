package tcep.graph.nodes

import java.io.{File, PrintStream, PrintWriter}
import java.util.{Timer, TimerTask}

import akka.actor.ActorRef
import customoperators.{CustomOperatorInterface, OperatorAddress}
import org.slf4j.LoggerFactory
import tcep.data.Events._
import tcep.data.Queries._
import tcep.graph.nodes.traits._
import tcep.graph.{CreatedCallback, EventCallback}
import tcep.placement.HostInfo
import tcep.graph.nodes.traits.Mode.Mode
import tcep.simulation.tcep.SimulationRunner.logger

import scala.language.postfixOps
import scala.sys.process._
import java.util.concurrent._


case class BenchmarkNode(mode: Mode,
                      hostInfo: HostInfo,
                      backupMode: Boolean,
                      mainNode: Option[ActorRef],
                      query: UnaryQuery,
                      @volatile var parentNode: ActorRef,
                      createdCallback: Option[CreatedCallback],
                      eventCallback: Option[EventCallback]
                     )
  extends UnaryNode {

  var n: Int = 0
  var k: Int = -1

  var lastMeasurement: Event5 = null
  var lastTimestamp = System.nanoTime();
  var firstWrite = true

  val directory = if(new File("./logs").isDirectory) Some(new File("./logs")) else {
    logger.info("Invalid directory path")
    None
  }
  val out = directory map { directory => new PrintStream(new File(directory, s"benchmark.csv"))
  } getOrElse java.lang.System.out

  val throughputOut = directory map { directory => new PrintStream(new File(directory, s"throughput.csv"))
  } getOrElse java.lang.System.out


  val scheduler = new Timer
  scheduler.scheduleAtFixedRate(new TimerTask() {
    override def run(): Unit = {
      if (lastMeasurement == null) {
        return
      }
      val endToEndLatency = lastTimestamp - BigInt(lastMeasurement.e4.toString)
      val processingLatency = lastTimestamp - BigInt(lastMeasurement.e5.toString)
      out.append(s"TCEP \t $n \t $k \t $endToEndLatency \t $processingLatency")
      out.println()
      k = -1
      n += 1
    }
  }, 0, 1000)

  /*val ex = new ScheduledThreadPoolExecutor(1)
  val task = new Runnable {
    def run() = {
      if (firstWrite) {
        firstWrite = false
        val pw = new PrintWriter("benchmark.csv")
        pw.close()
      }

      val eventLatency = lastTimestamp - BigInt(lastMeasurement.e4.toString)
      val processingLatency = lastTimestamp - BigInt(lastMeasurement.e4.toString)
      out.append(s"TCEP \t $n \t $k \t $eventLatency \t $processingLatency")
      out.println()
      k = -1
      n += 1;
    }
  }
  val f = ex.scheduleAtFixedRate(task, 0, 1, TimeUnit.SECONDS)*/

  //out.append(s"System \t Send time \t Receive time \t Latency \t CPU \t ID \t Amount \t isFraud")
  //out.println()

  override def childNodeReceive: Receive = super.childNodeReceive orElse {
    case event: Event => saveEvent(event)
    case unhandledMessage => log.info(s"unhandled message $unhandledMessage")
  }

  def saveEvent(event: Event): Unit = {
    if (k < 0) {
      k = 1
    } else {
      k += 1
    }
    if (!event.isInstanceOf[Event5]) {
      println("errornous event received")
      return
    }
    // println(s"received for save event $event $k")
    lastTimestamp = System.nanoTime()
    lastMeasurement = event.asInstanceOf[Event5]
  }

  def createDuplicateNode(hostInfo: HostInfo): ActorRef = {
    val startTime = System.currentTimeMillis()
    val res = null // NodeFactory.createFilterNode(mode, hostInfo, backupMode, mainNode, query, parentNode, createdCallback, eventCallback, context)
    log.info(s"Spent ${System.currentTimeMillis() - startTime} milliseconds to initialize BenchmarkNode")
    res
  }

  private def getCpuUsageByUnixCommand: Double = {
    val loadavg = "cat /proc/loadavg".!!;
    loadavg.split(" ")(0).toDouble
  }

  def maxWindowTime(): Int = 0
}

