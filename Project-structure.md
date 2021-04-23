### Project

 #### NodeManager
 NodeManager is a GoLang application that is deployed as a global service in the Docker Swarm network. A global service is replicated on all nodes that are in the network, essentially so that every machine in the network has one NodeManager.
NodeManager is responsible for receiving node-registrations from the running CEP system on the node and for starting/stopping/updating operators deployed on the node.

##### CEP node registrations
Every CEP node that is started on a network node needs to register at the NodeManager first in order to be able to use custom operators. For each registration it needs a identification string to identify the registered CEP node so that the system is able to host multiple different CEP nodes. 

 
 #### TCEP
   This folder includes the CEP system *TCEP* containing the *Custom Operator Interface* to be able to communicate with the abstraction layer. This interface can be found in the following directory: /src/main/scala/customoperators
