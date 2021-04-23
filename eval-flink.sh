# Author: Sebastian Hennig
#!/bin/bash

event_rate=1000
eval_time=13000
pre_update_time=240

CEPLESS=$PWD

mkdir -p $HOME/results/$event_rate
#cd $HOME/kafka/kafka_2.12-2.4.0
#screen -d -m bin/zookeeper-server-start.sh config/zookeeper.properties
#screen -d -m bin/kafka-server-start.sh config/server.properties

for i in {1..1}
do
    cd $CEPLESS/flink
    docker-compose restart
    docker-compose up -d
    cd $CEPLESS/
    screen -d -m bash -c "cd /home/manisha/lab-2020-acs-7-serveless-programming/code; ./run-flink.sh"

    #./run-flink.sh &
    echo "Starting evaluation $i"
    cd $CEPLESS/kafka-producer
    timeout $eval_time java -jar build/libs/kafka-producer.jar --topic op-test --num-records 1000000000 --producer-props bootstrap.servers=172.22.0.1:9092 --payload-file ../evaluation/cardtransactions-reduced.csv --throughput $event_rate
    echo "Finished evaluation $i"
    echo "Copying results to $HOME/results"
    cp $HOME/logs/benchmark.csv $HOME/results/$event_rate/$i.csv
done

cd $CEPLESS/flink
docker-compose down
#cd $HOME/logs
#rm benchmark.csv
#kill all detached sessions
#screen -ls | grep detached | cut -d. -f1 | awk '{print $1}' | xargs kill
screen -ls | grep "Detached" | awk '{ print $1; }' | cut -d'.' -f2- | xargs -I {} -n 1 screen -S {} -X quit
