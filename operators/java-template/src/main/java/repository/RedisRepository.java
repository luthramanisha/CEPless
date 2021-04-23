package repository;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.TransactionResult;
import io.lettuce.core.api.async.RedisAsyncCommands;
import manager.EventHandler;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import javax.transaction.TransactionRequiredException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

public class RedisRepository implements EventRepository {

    transient private EventHandler eventManager;

    transient private RedisClient redisReceiverClient;
    transient private RedisCommands<String, String> redisReceiverCommands;
    transient private RedisClient redisSenderClient;
    transient private RedisAsyncCommands<String, String> redisSenderCommands;
    transient private ArrayList<String> buffer;

    transient int outBatchSize = 1000;
    transient int inBatchSize = 1000;
    transient int backoffinc = 1;
    transient String addr;
    transient int flushInterval;

    transient Timer scheduler0;

    transient int i = 0;

    public RedisRepository(EventHandler eventHandler, String host, int port) {
        this.eventManager = eventHandler;

        this.redisReceiverClient = RedisClient.create("redis://" + host);
        StatefulRedisConnection<String, String> redisReceiverConnection = this.redisReceiverClient.connect();
        this.redisReceiverCommands = redisReceiverConnection.sync();

        this.redisSenderClient = RedisClient.create("redis://" + host);
        StatefulRedisConnection<String, String> redisConnection = this.redisSenderClient.connect();
        this.redisSenderCommands = redisConnection.async();
        this.redisSenderCommands.setAutoFlushCommands(false);
        buffer = new ArrayList<>();

        outBatchSize = Integer.parseInt(System.getenv("OUT_BATCH_SIZE"));
        inBatchSize = Integer.parseInt(System.getenv("IN_BATCH_SIZE"));
        flushInterval = Integer.parseInt(System.getenv("FLUSH_INTERVAL"));
        backoffinc = Integer.parseInt(System.getenv("BACK_OFF"));

        scheduler0 = new Timer();
        scheduler0.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                List<String> internalBuffer = new ArrayList<>(buffer);
                buffer.clear();
                int size = internalBuffer.size();
                int batches = 0;
                List<String> batch = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    if (batch.size() > outBatchSize) {
                        batches++;
                        redisSenderCommands.rpush(addr, batch.toArray(new String[batch.size()]));
                        batch.clear();
                    }
                    batch.add(internalBuffer.get(i));
                }
                if (batch.size() > 0) {
                    batches++;
                    redisSenderCommands.rpush(addr, batch.toArray(new String[batch.size()]));
                }
                long time = System.currentTimeMillis();
                // System.out.println("Starting flush with " + batches + " batches");
                redisSenderCommands.flushCommands();
                // System.out.println("Flush time: " + time);
            }
        }, 0, flushInterval);
    }

    @Override
    public void listen(String addr) {
        System.out.println("Receiving results on " + addr);
        Thread t = new Thread() {
            transient int backoff = 0;
            public void run() {
                // System.out.println("Receive thread started now");
                boolean lastListEmpty = false;
                while (true) {
                    try {
                        if (lastListEmpty) {
                            backoff = backoff + backoffinc;
                            // System.out.println("REDIS: Nothing to do. Sleeping for " + backoff);
                            TimeUnit.NANOSECONDS.sleep(backoff);
                            // System.out.println("REDIS: Wakeup after " + backoff);
                        }

                        redisReceiverCommands.multi();
                        redisReceiverCommands.lrange(addr, 0, inBatchSize - 1);
                        redisReceiverCommands.ltrim(addr, inBatchSize, -1);
                        TransactionResult result = redisReceiverCommands.exec();
                        List<String> list = result.get(0);

                        lastListEmpty = (list.size() == 0);

                        if (!lastListEmpty) {
                            backoff = 0;
                        }

                        list.forEach(item -> {
                            // System.out.println("REDIS: Processing list value " + item);
                            eventManager.process(item);
                        });
                    } catch (Exception e) {
                        System.out.println("Receive thread exception " + e.getMessage());
                    }
                }
            }
        };
        t.start();
    }

    @Override
    public void send(String addr, String item) {
        this.addr = addr;
        this.buffer.add(item);
    }
}


