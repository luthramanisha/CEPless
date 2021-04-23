package customoperators.repository

import java.util
import java.util.{Timer, TimerTask}
import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import customoperators.EventHandler
import io.lettuce.core.RedisClient

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.FiniteDuration

class RedisRepository(handler: EventHandler, host: String, port: Int) extends EventRepository {

  println(s"REDISREPOSITORY: Connecting to $host and $port")
  println(s"REDISREPO: $handler")

  private val receiverClient = RedisClient.create("redis://" + host)
  val redisReceiverConnection = this.receiverClient.connect
  private val receiverCommands = redisReceiverConnection.sync

  private val senderClient = RedisClient.create("redis://" + host)
  val redisConnection = this.senderClient.connect
  private var senderCommands = redisConnection.async
  senderCommands.setAutoFlushCommands(false)

  var receiver = handler

  var buffer = ArrayBuffer[String]()
  var sendAddr: Option[String] = _

  var outBatchSize = System.getenv("OUT_BATCH_SIZE").toInt
  var inBatchSize = System.getenv("IN_BATCH_SIZE").toInt
  var flushInterval = System.getenv("FLUSH_INTERVAL").toInt
  var backoffinc = System.getenv("BACK_OFF").toInt
  var sendThreadStarted = false

  override def listen(addr: String) {
    val t = new Thread() {
      var backoff = 0
      override def run(): Unit = {
        System.out.println("Receive thread started now")
        var lastListEmpty = false
        while ( {
          true
        }) try {
          if (lastListEmpty) {
            backoff = backoff + backoffinc
            //System.out.println("REDIS: Nothing to do. Sleeping for " + backoff)
            TimeUnit.NANOSECONDS.sleep(backoff)
            //System.out.println("REDIS: Wakeup after " + backoff)
          }
          receiverCommands.multi
          receiverCommands.lrange(addr, 0, inBatchSize - 1)
          receiverCommands.ltrim(addr, inBatchSize, -1)
          val result = receiverCommands.exec
          val list: util.Collection[String] = result.get(0)
          lastListEmpty = list.size == 0
          if (!lastListEmpty) backoff = 0
          val iterator = list.iterator()
          while (iterator.hasNext) {
            val item = iterator.next()
            //println(s"processing iterator item $item")
            // handler ! RepositoryEvent(item)
            handler.processElement(item)
            // context.parent ! RepositoryEvent(list.iterator().next())
          }
        } catch {
          case e: Exception =>
            e.printStackTrace()
            System.out.println("Receive thread exception " + e.getMessage)
        }
      }
    }
    t.start()
  }

  override def send(addr: String, value: String): Unit = {
    this.sendAddr = Option(addr)
    this.buffer += value

    if (!sendThreadStarted) {

      println("Sending started")
      sendThreadStarted = true
      val t = new Thread() {
        var backoff = 0
        override def run(): Unit = {
          while (true) {
            val internalBuffer = buffer.clone
            buffer.clear()
            val size = internalBuffer.size
            var batches = 0
            val batch = ArrayBuffer[String]()
            for (i <- 0 until size) {
              if (batch.size > outBatchSize) {
                batches += 1
                senderCommands.rpush(sendAddr.get, batch.toArray:_*)
                batch.clear()
              }
              batch += internalBuffer(i)
            }
            if (batch.size > 0) {
              batches += 1
              senderCommands.rpush(sendAddr.get, batch.toArray:_*)
            }
            if (batches > 0) {
              senderCommands.flushCommands()
              backoff = 0
            } else {
              backoff += backoffinc
              TimeUnit.NANOSECONDS.sleep(backoff);
            }
          }
        }
      }
      t.start()
    }
  }

  /*override def receive: Receive = {
    case addr: String => {
      listen(addr)
    }
    case tuple: Tuple2[String, String] => {
      send(tuple._1, tuple._2)
    }
    case unhandledMessage => println("REDIS: Unhandled message")
  }*/
}

case class RepositoryEvent(item: String)