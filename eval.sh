# Author: Sebastian Hennig
#!/bin/bash

event_rate=1000
eval_time=1300
pre_update_time=240

CEPLESS=$PWD

mkdir -p $HOME/results/$event_rate
#cd $HOME/kafka/kafka_2.12-2.4.0
#screen -d -m bin/zookeeper-server-start.sh config/zookeeper.properties
#screen -d -m bin/kafka-server-start.sh config/server.properties

for i in {1..3}
do
    cd $CEPLESS/flink
    docker-compose restart
    docker-compose up -d && \
    cd $CEPLESS
    screen -d -m -S flink-test bash -c "./run-flink.sh; sleep $pre_update_time; ./update-flink-query.sh"

    echo "Starting evaluation $i"
    cd $CEPLESS/kafka-producer
    timeout $eval_time java -jar build/libs/kafka-producer.jar --topic op-test --num-records 1000000000 --producer-props bootstrap.servers=172.18.0.1:9092 --payload-file ../evaluation/cardtransactions-reduced.csv --throughput $event_rate
    echo "Finished evaluation $i"
    echo "Copying results to $HOME/results"
    cp $HOME/logs/benchmark.csv $HOME/results/$event_rate/$i.csv

    screen -S flink-test -X quit
done

cd $CEPLESS/flink
docker-compose down
cd $HOME/logs
rm benchmark.csv