https://medium.com/@diegogabrielschurch/how-docker-network-works-bridge-driver-e4819459cc8a


Raft ensures that all servers in the cluster agree on the sequence of commands to be executed.
The replicated log maintained by Raft contains entries representing commands to be applied to the state machine.

Each server applies commands from the log to its local copy of the state machine in the same order they were logged, ensuring consistency across the cluster.

etcd is a distributed key-value store that uses Raft for consensus. It is widely used for service discovery, configuration management, and coordination.
The state machine in etcd is responsible for maintaining the key-value store and processing operations like "get(key)" and "put(key, value)".

CockroachDB is a distributed SQL database that offers horizontal scalability and fault tolerance.
It uses the Raft consensus algorithm for replication and consensus among nodes.
The state machine in CockroachDB manages the storage and retrieval of SQL data, ensuring consistency and durability across the cluster.


A port can be bound to a specific network interface

 `127.0.0.1:9092` and `192.168.1.1:9092` can both be used on the same host because they are bound to different network interfaces.

 it fails in kafka  with the below error
 `requirement failed: Each listener must have a different port, listeners: INTERNAL://localhost:9092,EXTERNAL://broker:9092`

 - Kafka listeners are unique combinations of protocol, IP address, and port.
- Kafka requires each listener to have a unique port, regardless of the IP address.