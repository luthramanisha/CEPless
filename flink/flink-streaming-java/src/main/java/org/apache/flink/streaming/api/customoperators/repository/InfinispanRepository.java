package org.apache.flink.streaming.api.customoperators.repository;

import org.apache.flink.streaming.api.customoperators.OperatorEventReceiver;
import org.infinispan.commons.util.ArrayMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Serializable;
import org.infinispan.client.hotrod.ProtocolVersion;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.annotation.ClientCacheEntryCreated;
import org.infinispan.client.hotrod.annotation.ClientCacheEntryModified;
import org.infinispan.client.hotrod.annotation.ClientListener;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.hotrod.event.ClientCacheEntryCreatedEvent;
import org.infinispan.client.hotrod.event.ClientCacheEntryModifiedEvent;

import java.util.*;

@ClientListener
public class InfinispanRepository implements EventRepository, Serializable {

	transient private OperatorEventReceiver eventHandler;
	transient private RemoteCacheManager cacheManager;
	transient private RemoteCache<String, String> channelOut;
	transient private RemoteCache<String, String> channelIn;
	transient private List<String> buffer;

	transient int outBatchSize;
	transient int inBatchSize;
	transient int flushInterval;
	transient String addr;

	transient Timer scheduler;

	Logger LOG = LoggerFactory.getLogger(InfinispanRepository.class);

	private int k = 1;

	public InfinispanRepository(OperatorEventReceiver eventHandler, String host, int port) {
		this.eventHandler = eventHandler;

		this.cacheManager = new RemoteCacheManager(new ConfigurationBuilder()
			.addServer()
				.host(host)
				.port(port)
			.version(ProtocolVersion.PROTOCOL_VERSION_26)
			.security()
				.authentication()
					.username("foo")
					.password("bar")
			.build());
		LOG.info("Initialization finished");

		this.buffer = Collections.synchronizedList(new ArrayList<>());

		outBatchSize = Integer.parseInt(System.getenv("OUT_BATCH_SIZE"));
		inBatchSize = Integer.parseInt(System.getenv("IN_BATCH_SIZE"));
		flushInterval = Integer.parseInt(System.getenv("FLUSH_INTERVAL"));
		System.out.println("Using infinispan configuration flush interval " + flushInterval);

		scheduler = new Timer();
		scheduler.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				List<String> internalBuffer = new ArrayList<>(buffer);
				buffer.clear();

				if (addr != null) {
					if (cacheManager.getCache(addr) == null) {
						cacheManager.administration().createCache(addr, null);
					}
					if (channelIn == null) {
						channelIn = cacheManager.getCache(addr);
					}
				}

				int size = internalBuffer.size();
				HashMap<String, String> batch = new HashMap<>();
				for (int i = 0; i < size; i++) {
					if (batch.size() > outBatchSize) {
						channelIn.putAllAsync(batch);
						batch.clear();
					}
					batch.put(Integer.toString(k++), internalBuffer.get(i));
				}
				if (batch.size() > 0) {
					channelIn.putAllAsync(batch);
				}
			}
		}, 0, flushInterval);
	}

	@Override
	public void listen(String addr) {
		LOG.info("Request for listen at addr " + addr);
		if (this.cacheManager.getCache(addr) == null) {
			LOG.info("Creating cache " + addr);
			this.cacheManager.administration().createCache(addr, null);
		}

		LOG.info("Listen at " + addr);
		this.channelOut = this.cacheManager.getCache(addr);
		this.channelOut.addClientListener(this);
		LOG.info("Listen done at " + addr);
	}

	@Override
	public void send(String addr, String item) {
		this.addr = addr;
		this.buffer.add(item);
	}

	private void itemReceived(String key) {
		if (this.channelOut != null) {
			String value = this.channelOut.get(key);
			this.eventHandler.receivedEvent(value);
		} else {
			System.out.println("ChannelOut not initialized");
		}
	}

	@ClientCacheEntryCreated
	public void handleCreatedEvent(ClientCacheEntryCreatedEvent e) {
		this.itemReceived((String)e.getKey());
	}

	@ClientCacheEntryModified
	public void handleModifiedEvent(ClientCacheEntryModifiedEvent e) {
		itemReceived((String)e.getKey());
	}
}

