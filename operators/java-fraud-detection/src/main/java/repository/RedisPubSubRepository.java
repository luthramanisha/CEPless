package repository;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import manager.EventHandler;

import java.io.*;
import java.util.Timer;
import java.util.TimerTask;

public class RedisPubSubRepository implements EventRepository {

    transient private EventHandler eventManager;

    transient private RedisClient receiverClient;
    transient private StatefulRedisPubSubConnection<String, String> receiverConnection;
    transient private RedisPubSubCommands<String, String> receiverCommands;

    transient private RedisClient senderClient;
    transient private StatefulRedisConnection<String, String> senderConnection;
    transient private RedisCommands<String, String> senderCommands;

    private int k = 0;
    transient private Timer scheduler;

    public RedisPubSubRepository(EventHandler eventHandler, String host, int port) {
        this.eventManager = eventHandler;

        this.receiverClient = RedisClient.create("redis://" + host);
        this.receiverConnection = this.receiverClient.connectPubSub();
        this.receiverCommands = this.receiverConnection.sync();

        this.senderClient = RedisClient.create("redis://" + host);
        this.senderConnection = this.senderClient.connect();
        this.senderCommands = this.senderConnection.sync();

        try {
            scheduler = new Timer();
            scheduler.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    try {
                        writeThroughputToCSV();
                        k = 0;
                    } catch (Exception e) {
                        System.out.println(e.getStackTrace());
                    }
                }
            }, 60 * 1000, 1000);
        } catch (Exception e) {
            System.out.println("Failed to schedule throughput measures");
            System.out.println(e.getStackTrace());
        }

    }

    @Override
    public void listen(String addr) {
        System.out.println("Receiving results on " + addr);

        receiverConnection.addListener(new RedisPubSubListener<String, String>() {
            @Override
            public void message(String s, String s2) {
                if (s == null) {
                    System.out.println("Received timeout");
                } else {
                    System.out.println("Received value " + s + " and " + s2 + " at " + System.currentTimeMillis());
                    eventManager.process(s);
                }
            }

            @Override
            public void message(String s, String k1, String s2) {
                if (s == null) {
                    System.out.println("Received timeout");
                } else {
                    System.out.println("Received value " + s + " at " + System.currentTimeMillis());
                    eventManager.process(s);
                }
            }

            @Override
            public void subscribed(String s, long l) {
                System.out.println("Subscribed to channel " + s);
            }

            @Override
            public void psubscribed(String s, long l) {
                System.out.println("PSubscribed to channel " + s);
            }

            @Override
            public void unsubscribed(String s, long l) {
                System.out.println("Unsubscribed from channel " + s);
            }

            @Override
            public void punsubscribed(String s, long l) {
                System.out.println("PUnsubscribed from channel " + s);
            }
        });
        this.receiverCommands.subscribe(addr);
        try {
            Thread.currentThread().join();
        } catch (Exception e) {
            System.out.println("Could not create listen thread");
        }
    }

    @Override
    public void send(String addr, String item) {
        k++;
        System.out.println("Sending value " + item + " to " + addr);
        this.senderCommands.publish(addr, item);
    }

    private void writeThroughputToCSV() {
        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter("throughput.csv", true)))) {
            writer.write("Flink \t" + k + "\t" + 100000 + "\n");
            System.out.println("done!");
        } catch (FileNotFoundException e) {
            System.out.println(e.getMessage());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
