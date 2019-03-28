##  FastLeaderElection

FastLeaderElection选举算法是标准的Fast Paxos算法实现，可解决LeaderElection选举算法收敛速度慢的问题。

### 3.3.1 服务器状态

- **LOOKING** 不确定Leader状态。该状态下的服务器认为当前集群中没有Leader，会发起Leader选举
- **FOLLOWING** 跟随者状态。表明当前服务器角色是Follower，并且它知道Leader是谁
- **LEADING** 领导者状态。表明当前服务器角色是Leader，它会维护与Follower间的心跳
- **OBSERVING** 观察者状态。表明当前服务器角色是Observer，与Folower唯一的不同在于不参与选举，也不参与集群写操作时的投票

### 3.3.2 选票数据结构

每个服务器在进行领导选举时，会发送如下关键信息

- **logicClock** 每个服务器会维护一个自增的整数，名为logicClock，它表示这是该服务器发起的第多少轮投票
- **state** 当前服务器的状态
- **self_id** 当前服务器的myid
- **self_zxid** 当前服务器上所保存的数据的最大zxid
- **vote_id** 被推举的服务器的myid
- **vote_zxid** 被推举的服务器上所保存的数据的最大zxid



## 3.4 几种领导选举场景

### 3.4.1 集群启动领导选举

**初始投票给自己** 
集群刚启动时，所有服务器的logicClock都为1，zxid都为0。

各服务器初始化后，都投票给自己，并将自己的一票存入自己的票箱，如下图所示。 
![Cluster start election step 1](http://www.jasongj.com/img/zookeeper/1_architecture/start_election_1.png)

在上图中，(1, 1, 0)第一位数代表投出该选票的服务器的logicClock，第二位数代表被推荐的服务器的myid，第三位代表被推荐的服务器的最大的zxid。由于该步骤中所有选票都投给自己，所以第二位的myid即是自己的myid，第三位的zxid即是自己的zxid。

此时各自的票箱中只有自己投给自己的一票。

**更新选票** 
服务器收到外部投票后，进行选票PK，相应更新自己的选票并广播出去，并将合适的选票存入自己的票箱，如下图所示。 
![Cluster start election step 2](http://www.jasongj.com/img/zookeeper/1_architecture/start_election_2.png)

服务器1收到服务器2的选票（1, 2, 0）和服务器3的选票（1, 3, 0）后，由于所有的logicClock都相等，所有的zxid都相等，因此根据myid判断应该将自己的选票按照服务器3的选票更新为（1, 3, 0），并将自己的票箱全部清空，再将服务器3的选票与自己的选票存入自己的票箱，接着将自己更新后的选票广播出去。此时服务器1票箱内的选票为(1, 3)，(3, 3)。

同理，服务器2收到服务器3的选票后也将自己的选票更新为（1, 3, 0）并存入票箱然后广播。此时服务器2票箱内的选票为(2, 3)，(3, ,3)。

服务器3根据上述规则，无须更新选票，自身的票箱内选票仍为（3, 3）。

服务器1与服务器2更新后的选票广播出去后，由于三个服务器最新选票都相同，最后三者的票箱内都包含三张投给服务器3的选票。

**根据选票确定角色** 
根据上述选票，三个服务器一致认为此时服务器3应该是Leader。因此服务器1和2都进入FOLLOWING状态，而服务器3进入LEADING状态。之后Leader发起并维护与Follower间的心跳。 
![Cluster start election step 3](http://www.jasongj.com/img/zookeeper/1_architecture/start_election_3.png)

### 3.4.2 Follower重启

**Follower重启投票给自己** 
Follower重启，或者发生网络分区后找不到Leader，会进入LOOKING状态并发起新的一轮投票。 
![Follower restart election step 1](http://www.jasongj.com/img/zookeeper/1_architecture/follower_restart_election_1.png)

**发现已有Leader后成为Follower** 
服务器3收到服务器1的投票后，将自己的状态LEADING以及选票返回给服务器1。服务器2收到服务器1的投票后，将自己的状态FOLLOWING及选票返回给服务器1。此时服务器1知道服务器3是Leader，并且通过服务器2与服务器3的选票可以确定服务器3确实得到了超过半数的选票。因此服务器1进入FOLLOWING状态。 
![Follower restart election step 2](http://www.jasongj.com/img/zookeeper/1_architecture/follower_restart_election_2.png)

### 3.4.3 Leader重启

**Follower发起新投票** 
Leader（服务器3）宕机后，Follower（服务器1和2）发现Leader不工作了，因此进入LOOKING状态并发起新的一轮投票，并且都将票投给自己。 
![Leader restart election step 1](http://www.jasongj.com/img/zookeeper/1_architecture/leader_restart_election_1.png)

**广播更新选票** 
服务器1和2根据外部投票确定是否要更新自身的选票。这里有两种情况 
\- 服务器1和2的zxid相同。例如在服务器3宕机前服务器1与2完全与之同步。此时选票的更新主要取决于myid的大小 
\- 服务器1和2的zxid不同。在旧Leader宕机之前，其所主导的写操作，只需过半服务器确认即可，而不需所有服务器确认。换句话说，服务器1和2可能一个与旧Leader同步（即zxid与之相同）另一个不同步（即zxid比之小）。此时选票的更新主要取决于谁的zxid较大

在上图中，服务器1的zxid为11，而服务器2的zxid为10，因此服务器2将自身选票更新为（3, 1, 11），如下图所示。 
![Leader restart election step 2](http://www.jasongj.com/img/zookeeper/1_architecture/leader_restart_election_2.png)

**选出新Leader** 
经过上一步选票更新后，服务器1与服务器2均将选票投给服务器1，因此服务器2成为Follower，而服务器1成为新的Leader并维护与服务器2的心跳。 

![Leader restart election step 3](http://www.jasongj.com/img/zookeeper/1_architecture/leader_restart_election_3.png)

**旧Leader恢复后发起选举** 
旧的Leader恢复后，进入LOOKING状态并发起新一轮领导选举，并将选票投给自己。此时服务器1会将自己的LEADING状态及选票（3, 1, 11）返回给服务器3，而服务器2将自己的FOLLOWING状态及选票（3, 1, 11）返回给服务器3。如下图所示。 
![Leader restart election step 4](http://www.jasongj.com/img/zookeeper/1_architecture/leader_restart_election_4.png)

**旧Leader成为Follower** 
服务器3了解到Leader为服务器1，且根据选票了解到服务器1确实得到过半服务器的选票，因此自己进入FOLLOWING状态。 
![Leader restart election step 5](http://www.jasongj.com/img/zookeeper/1_architecture/leader_restart_election_5.png)

# 4 一致性保证

ZAB协议保证了在Leader选举的过程中，已经被Commit的数据不会丢失，未被Commit的数据对客户端不可见。

## 4.1 Commit过的数据不丢失

**Failover前状态** 
为更好演示Leader Failover过程，本例中共使用5个Zookeeper服务器。A作为Leader，共收到P1、P2、P3三条消息，并且Commit了1和2，且总体顺序为P1、P2、C1、P3、C2。根据顺序性原则，其它Follower收到的消息的顺序肯定与之相同。其中B与A完全同步，C收到P1、P2、C1，D收到P1、P2，E收到P1，如下图所示。 
![Leader Failover step 1](http://www.jasongj.com/img/zookeeper/1_architecture/recovery_1.png)

这里要注意

- 由于A没有C3，意味着收到P3的服务器的总个数不会超过一半，也即包含A在内最多只有两台服务器收到P3。在这里A和B收到P3，其它服务器均未收到P3
- 由于A已写入C1、C2，说明它已经Commit了P1、P2，因此整个集群有超过一半的服务器，即最少三个服务器收到P1、P2。在这里所有服务器都收到了P1，除E外其它服务器也都收到了P2

**选出新Leader** 
旧Leader也即A宕机后，其它服务器根据上述FastLeaderElection算法选出B作为新的Leader。C、D和E成为Follower且以B为Leader后，会主动将自己最大的zxid发送给B，B会将Follower的zxid与自身zxid间的所有被Commit过的消息同步给Follower，如下图所示。 
![Leader Failover step 2](http://www.jasongj.com/img/zookeeper/1_architecture/recovery_2.png)

在上图中

- P1和P2都被A Commit，因此B会通过同步保证P1、P2、C1与C2都存在于C、D和E中
- P3由于未被A Commit，同时幸存的所有服务器中P3未存在于大多数据服务器中，因此它不会被同步到其它Follower

**通知Follower可对外服务** 
同步完数据后，B会向D、C和E发送NEWLEADER命令并等待大多数服务器的ACK（下图中D和E已返回ACK，加上B自身，已经占集群的大多数），然后向所有服务器广播UPTODATE命令。收到该命令后的服务器即可对外提供服务。 
![Leader Failover step 3](http://www.jasongj.com/img/zookeeper/1_architecture/recovery_3.png)