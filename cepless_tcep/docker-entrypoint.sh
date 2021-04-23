#!/bin/bash
iperf3 -s -D
ntpd -s
#tc qdisc add dev eth0 root netem delay ${DELAY} 50ms #no artificial delay on GENI testbed required!
java -Done-jar.main.class=${MAIN} -Dconstants.gui-endpoint="http://gui:3000" -jar /app/tcep_2.12-0.0.1-SNAPSHOT-one-jar.jar ${ARGS}
