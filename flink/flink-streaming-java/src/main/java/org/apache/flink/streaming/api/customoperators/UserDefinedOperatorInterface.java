package org.apache.flink.streaming.api.customoperators;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.flink.streaming.api.customoperators.repository.EventRepository;
import org.apache.flink.streaming.api.customoperators.repository.InfinispanRepository;
import org.apache.flink.streaming.api.customoperators.repository.RedisPubSubRepository;
import org.apache.flink.streaming.api.customoperators.repository.RedisRepository;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Interface for communication between UD operator <> Flink engine
 */
public class UserDefinedOperatorInterface implements Serializable, OperatorEventReceiver {

	// Event queue
	transient private EventRepository repository;

	private HashMap<CustomOperatorAddress, OperatorEventReceiver> listeners = new HashMap<CustomOperatorAddress, OperatorEventReceiver>();
	private HashMap<String, CustomOperatorAddress> operators = new HashMap<String, CustomOperatorAddress>();

	Logger LOG = LoggerFactory.getLogger(UserDefinedOperatorInterface.class);

	/**
	 * Requests an operator at the local node manager to be deployed
	 * @param operatorName Name of the operator
	 * @param callback Object to be notified as soon as deployment was successfully invoked
	 * @throws IOException
	 */
	public void requestOperator(String operatorName, CustomOperatorDeployed callback) throws IOException {
		String localhostname = java.net.InetAddress.getLocalHost().getHostName();
		String opRequestIdentifier = localhostname + "-" + UUID.randomUUID().toString() + "-" + operatorName;

		// Build operator HTTP request
		HashMap<String, String> data = new HashMap<>();
		data.put("name", operatorName);
		data.put("requestIdentifier", opRequestIdentifier);

		Gson gson = new Gson();
		String json = gson.toJson(data);

		CloseableHttpClient httpClient = HttpClientBuilder.create().build();
		String addr = System.getenv("NODE_MANAGER_HOST");

		HttpPost request = new HttpPost("http://" + addr + "/requestOperator");
		StringEntity params = new StringEntity(json);
		request.addHeader("content-type", "application/json");
		request.setEntity(params);
		HttpResponse response = httpClient.execute(request);
		System.out.println(response);

		String jsonString = EntityUtils.toString(response.getEntity());
		Type type = new TypeToken<Map<String, String>>(){}.getType();
		Map<String, String> result = gson.fromJson(jsonString, type);

		System.out.println(result);

		String addrIn = result.get("addrIn");
		String addrOut = result.get("addrOut");
		LOG.info("Using addrIn " + addrIn + " and addrOut " + addrOut + " for operator request " + opRequestIdentifier);

		// Instantiate operator address for routing events
		CustomOperatorAddress address = new CustomOperatorAddress(addrIn, addrOut);
		operators.put(opRequestIdentifier, address);
		callback.notifyOperatorDeployed(opRequestIdentifier, address);
		this.repository = getRepository();
		this.repository.listen(addrOut);
	}

	/**
	 * Sends an event to a UD operator
	 * @param value Event to be sent
	 * @param address Address of the receiving UD operator
	 */
	public void sendEvent(String value, CustomOperatorAddress address) {
		if (this.repository == null) {
			this.repository = getRepository();
		}
		this.repository.send(address.addrIn, value);
	}

	/**
	 * Adds an event listener for new UD operator events
	 * @param operatorAddress Address of the UD operator to be listened to
	 * @param receiver Object to be notified
	 * @return bool value indicating if the listener was added
	 */
	public boolean addListener(CustomOperatorAddress operatorAddress, OperatorEventReceiver receiver) {
		LOG.info("Adding listener for address " + operatorAddress.addrOut);
		System.out.println("Adding listener for address " + operatorAddress.addrOut);
		if (listeners.get(operatorAddress) != null && listeners.get(operatorAddress).equals(receiver)) {
			return false;
		}
		listeners.put(operatorAddress, receiver);
		return true;
	}

	/**
	 * Returns an instance of the event queue based on application config
	 * @return EventRepository instance
	 */
	private EventRepository getRepository() {
		String dbType = System.getenv("DB_TYPE");
		if (dbType.equals("infinispan")) {
			String host = System.getenv("INFINISPAN_HOST");
			return new InfinispanRepository(this, host, 11222);
		} else if (dbType.equals("redis-pubsub")) {
			String host = System.getenv("REDIS_HOST");
			return new RedisPubSubRepository(this, host, 6379);
		} else {
			String host = System.getenv("REDIS_HOST");
			return new RedisRepository(this, host, 6379);
		}
	}

	/**
	 * Called when an event from the EventRepository was received
	 * @param event Received event
	 */
	@Override
	public void receivedEvent(String event) {
		listeners.forEach((k, v) -> {
			listeners.get(k).receivedEvent(event);
		});
	}
}
