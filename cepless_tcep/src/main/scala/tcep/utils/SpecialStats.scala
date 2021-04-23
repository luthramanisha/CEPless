package tcep.utils

import java.io.{BufferedOutputStream, File, FileOutputStream, PrintStream}
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.concurrent.{Executors, TimeUnit}

import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.util.Random

/**
  * Saves special stats in stats.log file
  * Created on 17/01/2018.
  */
object SpecialStats {
  //TODO: take directory path from configuration

  val debugID = Random.nextInt()
  val debug = new mutable.StringBuilder()
  val logger = LoggerFactory.getLogger(getClass)
  val log = new mutable.HashMap[String, String]()
  val startTime = System.currentTimeMillis()

  private val refreshInterval = 10
  val refreshTask = new Runnable {
    def run() = {
      publish()
    }
  }

  Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(refreshTask, 10, refreshInterval, TimeUnit.SECONDS)

  def publish(): Unit = {

    log.keys.foreach(k => {
      val stats = new PrintStream(new BufferedOutputStream(new FileOutputStream(new File("./logs", s"stats-$k-$debugID.csv"), true)))
      stats.append(log(k))
      stats.flush()
      stats.close()
    })
    log.clear()

    val debugFile = new PrintStream(new BufferedOutputStream(new FileOutputStream(new File("./logs", s"debug-$debugID.csv"), true)))
    debugFile.append(debug)
    debugFile.flush()
    debugFile.close()
    debug.clear()
  }

  def log(caller: String, tag: String, msg: String): Unit = synchronized {
    val oldvalue = log.getOrElse(tag, "")
    log.put(tag, s"$oldvalue$timestamp;$caller;$msg\n")
  }


  def debug(caller: String, msg: String): Unit = {
    debug.append(s"$timestamp;$caller;$msg\n")
  }

  def timestamp: String = {
    val now = Calendar.getInstance().getTime
    val passed = now.getTime - startTime
    val df = new SimpleDateFormat("HH:mm:ss.SSS")
    s"${df.format(now)};${df.format(passed)}"
  }
}
