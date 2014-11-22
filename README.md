# akka-availability-management

This project aims to manage nodes in Akka cluster, where 
Availability and Partitions are the primary requirements. 


## Redundant Client Server Architecture

In some problem domains, client server architectures are still common.
These have N client nodes and a server node, interconnected by a switch. 

Redundancy is commonly implemented by creating two complete instances (A and B),
with their switches interconnected. Generally one server is active and
the other is standby. 

A failure of the switch interconnect(s) will create a _split brain_ condition,
with each side acting independently. This is not detectable and generally 
acceptable, with the resultant data mismatch being resolved when the interconnect
is restored. 

A switch failure will island every node in that instance, rendering it inoperative.

A server failure need not render its instance inoperative, since its client nodes can
use the server in the other instance. 

### CAP implications

Such an architecture is biased towards AP, with Consistency being a best effort.
Note there are only two instances, eliminating the possibility of a quorum based 
design. 


## AKKA Cluster implementation

This provides a useful membership service that we should be able to use as the
basis of the above architecture. However, the service is biased towards CA.
In particular, the cluster leader is not fully functional when nodes are (only) unreachable.
For example, losing a client node would prohibit the joining of a new client node. 

The _auto down_ facility allows the restoration of leader functionality by assuming an
unreachable node is actually failed. This leads to difficulty when the unreachable is not 
actually failed and becomes visible to the cluster (e.g. when a faulty network cable is fixed).
In this case, the cluster will ignore the now visible node. This impasse can only be
resolved by restarting at least one of the interacting ActorSystems (i.e. forcing a failure).

Such a restart destroys all the actors within the ActorSystem, impacting their design.
* An ActorSystem is only expected to shutdown when the entire node shuts down.
* The actor restart is caused by a completely extrinsic event, deriving from an implementation detail.

This issue has generated much discussion:
* https://groups.google.com/forum/#!topic/akka-user/ZrasD539-Ys
* https://groups.google.com/forum/#!topic/akka-user/U-UAadh50mM
* https://groups.google.com/forum/#!topic/akka-user/AdRSv2yuwo4

## Design
A _sacrifical_ ActorSystem is used within each node:
1. Determines cluster membership
2. Proxies messages between nodes
3. Restarts as needed to allow nodes to re-joining

A proxy design (much like [Cluster Client](http://doc.akka.io/docs/akka/snapshot/contrib/cluster-client.html))
minimizes the impact of the ActorSystem restart and decouples the membership service from the real functionality. 

A restart is required when:
* This node becomes a singleton
* The server in the other instance is downed


### Recovery
The ```Recovery``` object encapsulates the implementing actor. 

The controlled ActorSystem has to be (re)created
whenever necessitated by changes in the cluster connectivity, provided as a Function0 for maximum flexibility. 

The desired semantics are implemented by an _embedded_ Actor within the ActorSystem, created via a Function1. 
That Actor is expected to provided communication between the nodes of the cluster, with ```Redirector``` providing
a canonical implementation. 

The embedded actor will be sent any message, other than RegisterClusterListener, sent to RecoveryActor.

The embedded Actor might have have state that has to survive the restart. The EmbeddedDown message instructs the embedded
Actor to reply with its current and then shutdown. The provided state will then be sent to the newly created replacement instance.

The restart policy is provided via (previous:Set[Member], removed:Member)=>Boolean, which is called on each MemberRemoved event.
Previous contains all the members that were connected before the event. The standard master/slave design would return true if the
removed Member is the server in the other instance.

A client of Recovery can subscribe to the MemberEvents of the controlled ActorSystem, by sending ```RegisterClusterListener``` to the ```RecoveryActor```.

### Redirector
The ```Redirector``` object encapsulates the RedirectorActor.

The ```Register``` case class specifies an ActorRef that will receive messages delivered across the cluster.
The registration survives restart of the actor (and its containing ActorSystem). 

The ```Send``` case class requests the delivery of msg across the cluster. The target node(s) are identified by their
role. The handling ActorRef is determined via its name. 

## Improvements
1. Redirector does not attempt to cache remote ActorRefs, requiring a actorSelection call for every Send.

## Consequences
1. The ```sender()``` of the message provided to the handling ActorRef is the local Redirector instance. 
2. The remote communication between the nodes is hidden within the sacrificial Cluster.

This limitations on permissible communication can be helpful in high security systems that need to
explicitly manage all communication to/from a node.





