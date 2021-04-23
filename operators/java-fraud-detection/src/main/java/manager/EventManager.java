package manager;

import operator.OperatorProcessingInterface;
import repository.EventRepository;
import repository.InfinispanRepository;
import repository.RedisPubSubRepository;
import repository.RedisRepository;

import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Logger;

public class EventManager implements EventHandler {

    private EventRepository repository;
    private String addrIn;
    private String addrOut;
    private OperatorProcessingInterface operator;
    private int k = 0;
    private Timer scheduler;
    private static final Logger log = Logger.getLogger(EventManager.class.getName());

    public EventManager(String dbType, String dbHost, String addrIn, String addrOut, OperatorProcessingInterface operator) {
        log.info("Initializing EventManager");
        this.addrIn = addrIn;
        this.addrOut = addrOut;
        this.operator = operator;
        if (dbType.equals("infinispan")) {
            this.repository = new InfinispanRepository(this, dbHost, 11222);
        } else {
            this.repository = new RedisRepository(this, dbHost, 6379);
        }
        this.repository.listen(addrIn);
        scheduler = new Timer();
        scheduler.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                log.info("Received " +  k + " events per second");
                k = 0;
            }
        }, 0, 1000);
    }

    private void send(String item) {
        this.repository.send(this.addrOut, item);
    }

    @Override
    public void process(String item) {
        k++;
        // log.info("EVENTMANAGER: Processing value by UD function");
        String processed = this.operator.process(item);
        // log.info("Sending processed item " + processed + " to out-queue " + addrOut);
        this.send(processed);
    }
}
