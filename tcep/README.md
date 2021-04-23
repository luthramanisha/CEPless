# TCEP
TCEP is a research project that extends AdaptiveCEP[1] system to support transitions in different CEP mechanisms e.g., operator placement, corresponding to the change in the environmental conditions. 
The system currently support transitions between operator placement mechanisms. Towards this, following are the major contributions:

+ **Transition execution strategies** for a _cost-efficient_ and _seamless_ operator placement transition
+ **Interface** to implement state-of-the-art _operator placement_ mechanisms
+ Following state-of-the-art placement mechanisms are _provided_ by the system currently:
    + Pietzuch, P., Ledlie, J., Shneidman, J., Roussopoulos, M., Welsh, M., & Seltzer, M. (2006). Network-aware operator placement for stream-processing systems. Proceedings - International Conference on Data Engineering, 2006, 49. https://doi.org/10.1109/ICDE.2006.105
    + Starks, F., & Plagemann, T. P. (2015). Operator placement for efficient distributed complex event processing in MANETs. 2015 IEEE 11th International Conference on Wireless and Mobile Computing, Networking and Communications, WiMob 2015, 83–90. https://doi.org/10.1109/WiMOB.2015.7347944
    + Rizou, S., Dürr, F., & Rothermel, K. (2010). Solving the multi-operator placement problem in large-scale operator networks. Proceedings - International Conference on Computer Communications and Networks, ICCCN. https://doi.org/10.1109/ICCCN.2010.5560127

## Pre-requisites:
* Docker
* docker-machine
* Docker registry account
* java (jdk and jre)

## Running Docker simulation

Simulation can be executed using a GUI or without a GUI. To configure the simulation to run with a GUI you'll need to replace the execution mode in the stack file template before generating the docker-stack file. 
For GUI simulation you'll need to set the simulation mode to 7 in `scripts/templates/simulator-docker.yml`.

A full list of simulations can be found below: 

| Mode  | Description  |
|---|---|
|  1  | Test Relaxation algorithm  | 
|  2 | Test Starks algorithm | 
| 3 | Test SMS transition |
| 4 | Test MFGS transition |
| 5 | Test SPLC data collection |
| 6 | Do nothing | 
| 7 | Test with GUI |
| 8 | Test Rizou |

### Ubuntu users:
* Make sure you have installed the pre-requisites
* Make sure that docker has atleast 6 GB RAM (simulation wont work if RAM is insufficient)
* Run publish_docker.sh script with
 ```
 bash publish_docker.sh all $user@$IP 
 ```
* the script will launch docker containers pertaining to publishers, subscribers and brokers (EmptyApps) corresponding to the query described in the simulation
* to make sure the docker containers are started use docker ps
* use docker exec -it <name> bash to shell into the docker containers
* run tail -f "tcep.log" to see live logs
* Run publish_docker_changing_environment.sh to see the results of changing environment simulation

## Docker Registry

We use docker registry to make the distribution of docker images to all nodes easier. You'll need to register your own account at https://hub.docker.com/ in order to be able to push images on your own.
As soon as you have successfully registered, enter your chosen username in the docker-swarm.cfg

```
registry_user=your-username
```

## Running on GENI

GENI provides a large-scale experiment infrastructure where users can obtain computing instances throughout the United States to perform network experiments.
TransitiveCEP includes useful scripts to enable easier simulations on GENI which are described below.

```
cd scripts/
```

First, a so called RSpec XML file is needed in order to get a GENI cluster up and running. To automatically generate a cluster with a specified number of nodes you can execute the following command:

```
python generate_geni_rspec.py {number-of-nodes} {out-directory}
```

This will generate the rspec.xml file with the at "out-directory" with the specified number of nodes. Furthermore, this also generate the Docker swarm file with the correct amount of empty apps running on the GENI hosts.

After you deployed the RSpec on GENI, you can download a Manifest XML file which contains information of the hosts that GENI deployed. This is useful because GENI automatically generates IP addresses for the hosts and if you created a cluster with a high amount of nodes the search for this IP addresses can be a heavy overhead.
After downloading the manifest file you can run the following command to extract the IP addresses out of it and print out the config that can be put into the "docker-swarm.cfg" file:

```
python manifest_to_config.py {manifest-path}
```

This will convert the manifest and will print out the IP addresses of the started hosts in the config format for the docker deploy script.
You should see an output like this

```
manager=72.36.65.68
workers=("72.36.65.69" "72.36.65.70")
```

Now the hosts are successfully deployed on GENI and the project is ready to be setup on the hosts. To setup the GENI instances to be able to run docker, run the following command

```
./publish_docker.sh setup
```

Note that you maybe have to enter `yes` in the console multiple times to allow SSH connections to the hosts

If you are running on a new cluster you never worked on before, you will maybe need to authorize docker on the master node to be authorized to access a private docker registry. You can do this by executing the following on the master node

```
docker login
```

After the instances are all setup, you can go forward and finally run the cluster on the hosts by executing the following command

```
./publish_docker.sh all
```

## Running on CloudLab

CloudLab is comparable to the GENI infrastructure but enables TCEP the possibility to start instances that have Docker installed already.
The steps from above are the same, except that you have to replace the name of the RSpec image to be used before calling the generate script.

Specifically, you have to replace this line in scripts/templates/rspec_node.xml 

``` 
<disk_image xmlns="http://www.geni.net/resources/rspec/3" name="urn:publicid:IDN+emulab.net+image+emulab-ops:UBUNTU16-64-STD"/>
```

with this line

``` 
<disk_image xmlns="http://www.geni.net/resources/rspec/3" name="urn:publicid:IDN+utah.cloudlab.us+image+schedock-PG0:docker-ubuntu16:0"/>
```

## Running on MAKI cluster

The MAKI cluster is already setup for use with docker and does not need any configuration in terms of installing software.
To run TCEP on MAKI cluster you will need to modify the docker-swarm.cfg to reflect the hosts you are working on for example

```
manager=10.2.1.15
workers=("10.2.1.42")
```

After this is done, you can run the setup script 

```
./publish_docker.sh setup
```

and build as well as run the project

```
./build.sh && ./scripts/publish_tcep.sh publish
```

#Additional Resources:

[1] M. Luthra, B. Koldehofe, R. Arif, P. Weisenburger, G. Salvaneschi, TCEP: Adapting to Dynamic User Environments by Enabling Transitions between Operator Placement Mechanisms. In the Proceedings of 12th ACM International Conference on Distributed and Event-based Systems (DEBS) 2018

[2] P. Weisenburger, M. Luthra, B. Koldehofe, G. Salvaneschi: Quality-aware Runtime Adaptation in Complex Event Processing. In 2017 IEEE/ACM 12th International Symposium on Software Engineering for Adaptive and Self-Managing Systems (SEAMS) 2017
http://ieeexplore.ieee.org/document/7968142/

Introduction to PUMBA: 
https://www.linkedin.com/pulse/pumba-chaos-testing-docker-alexei-ledenev/
https://hackernoon.com/pumba-chaos-testing-for-docker-1b8815c6b61e

## Introduction to AdaptiveCEP

AdaptiveCEP is a research project exploring ways to embed quality demands into queries for event processing systems. As such, its main contributions are:

+ **AdaptiveCEP DSL** for _expressing_ EP queries including quality demands
+ **AdaptiveCEP Akka/Esper Backend** for _executing_ EP queries including quality demands

AdaptiveCEP DSL is simply an embedded Scala DSL. AdaptiveCEP Akka/Esper Backend is a tree of Akka actors representing a query, where each actor represents a primitive or an operator of the query.

### AdaptiveCEP DSL

AdaptiveCEP DSL is the domain-specific language to express event processing queries. Queries are made up of primitives and operators and can be arbitrarily nested and composed.

Internally, queries expressed using AdaptiveCEP DSL are represented by case classes. This case class representation can then be passed to AdaptiveCEP Akka/Esper Backend for execution, but it may also be interpreted by another backend.

#### Examples

+ Joining streams

    + Example 1: `join` of two streams:

    ```scala
    val q1 = streamA join streamB in (slidingWindow(3 seconds), tumblingWindow(3 instances))
    ```

    + Example 2: `join` composing a stream with another subquery:

    ```scala
    val q2 = streamC join q1 in (slidingWindow(2 instances), tumblingWindow(2 seconds))
    ```

    + Available windows:

    ```scala
    slidingWindow(x instances)
    slidingWindow(x seconds)
    tumblingWindow(x instances)
    tumblingWindow(x seconds)
    ```

+ Filtering streams

    + Example 1: Only keep those event instances `where` element 2 is smaller than 10.

    ```scala
    val q3 = streamA where { (_, value, _) => value < 10 }
    ```

    + Example 2: Only keep those event instances `where` element 1 equals element 2.

    ```scala
    val q4 = streamA where { (value1, value2, _) => value1 == value2 }
    ```

+ Quality demand

    ```scala
    val q5 =
      (streamA
        demand (frequency > ratio(3 instances, 5 seconds))
        where { (_, value, _) => value < 10 })
    ```


## TransitiveCEP Akka/Esper Backend

As said, the AdaptiveCEP backend is a tree of Akka actors representing a query, with each actor representing a primitive or an operator of that query. Given a query as well as the `ActorRef`s to the event publishers, the backend will automatically build and start emitting events.

A node representing a primitive (so far, there's only one primitive: a subscription to a stream) is just a plain Akka actor subscribing to the respective publisher, whereas nodes representing operators are all running an independent instance of the Esper event processing engine.
