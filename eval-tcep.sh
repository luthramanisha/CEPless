# Author: Sebastian Hennig
#!/bin/bash

event_rate=1000
eval_time=1300

mkdir -p $HOME/results-tcep/$event_rate

for i in {1..3}
do
    cd $HOME/ba-sebastian/tcep
    docker-compose restart
    docker-compose up -d
    cd $HOME/ba-sebastian

    echo "Starting evaluation $i"
    cd $HOME/ba-sebastian/kafka-producer
    timeout $eval_time java -jar build/libs/kafka-producer.jar --topic op-test --num-records 1000000000 --producer-props bootstrap.servers=172.24.0.1:9092 --payload-file ../evaluation/cardtransactions-reduced.csv --throughput $event_rate
    echo "Finished evaluation $i"
    echo "Copying results to $HOME/results"
    cp $HOME/logs/benchmark.csv $HOME/results-tcep/$event_rate/$i.csv
done
