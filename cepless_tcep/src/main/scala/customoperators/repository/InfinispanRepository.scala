package customoperators.repository

import java.util.concurrent.CompletableFuture

import customoperators.EventHandler
import org.infinispan.client.hotrod.{RemoteCache, RemoteCacheManager}
import org.infinispan.client.hotrod.annotation.{ClientCacheEntryCreated, ClientCacheEntryModified, ClientListener}
import org.infinispan.client.hotrod.event.{ClientCacheEntryCreatedEvent, ClientCacheEntryModifiedEvent}

import scala.concurrent._
import scala.concurrent.Future
import scala.util.{Failure, Success}

@ClientListener
class InfinispanRepository(handler: EventHandler, host: String, port: Int) extends EventRepository {

  private var cacheManager: RemoteCacheManager = new RemoteCacheManager();
  private var channelIn: RemoteCache[String, String] = _
  private var channelOut: RemoteCache[String, String] = _
  private var channelOutName: String = _

  private val eventHandler: EventHandler = handler

  private var k = 0

  def listen(addr: String): Unit = {
    channelOutName = addr
    if (this.cacheManager.getCache(addr) == null) {
      val str: String = null
      this.cacheManager.administration().createCache(addr, str)
    }
    println("Infinispan: Listen on " + addr)
    this.channelOut = this.cacheManager.getCache(addr)
    this.channelOut.addClientListener(this)
  }

  def send(addr: String, item: String): Unit = {
    println("Infinispan: Send item " + item + " to " + addr)
    if (this.cacheManager.getCache(addr) == null) {
      val str: String = null
      this.cacheManager.administration().createCache(addr, str)
    }
    if (this.channelIn == null) {
      this.channelIn = this.cacheManager.getCache(addr)
    }
    k += 1
    this.channelIn.put(k.toString, item)
  }

  def itemReceived(key: String): Unit = {
    /*if (this.channelOut != null) {
      println("Getting value for key " + key + " " + channelOutName)
      var f: Future[String] = scala.compat.java8.FutureConverters.toScala(channelOut.getAsync(key))

      f onComplete {
        case Success(result) =>  {
          println("Infinispan received " + result)
          this.eventHandler.processElement(result)
        }
        case Failure(t) => println("An error has occurred: " + t.getMessage)
      }
    } else {
      println("ChannelOut not initialized")
    }*/
  }

  @ClientCacheEntryCreated
  def handleCreatedEvent(e: ClientCacheEntryCreatedEvent[String]): Unit = {
    println("Created event " + e.toString)
    itemReceived(e.getKey)
  }

  @ClientCacheEntryModified
  def handleModifiedEvent(e: ClientCacheEntryModifiedEvent[String]): Unit = {
    println("Modified event " + e.toString)
    itemReceived(e.getKey)
  }

  // override def receive: Receive = ???
}
