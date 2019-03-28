## 1.master和slave的职责

### leader

   leader是zookeeper集群的主节点，负责响应所有对zookeeper状态变更的请求（事务性更新和非事务性查询）

   对于exists，getData，getChildren等非事务性查询请求，zookeeper服务器直接本地处理，每个服务器的命名空间是一致的。对于create，setData，delete等事务性更新请求，需要统一转发给leader处理，leader保

证2-阶段或者3-阶段来处理请求。

### follower

   follower响应非事务性查询，还可以处理leader的提议，并在leader提交该提议时在本地提交。leader和follower构成zookeeper集群的法定人数，参与新leader的选举、响应leader的提议。



## 2.failover

请见演示



## 3.选主算法

见[zk选主算法.md](file:///Users/wangwang/Repo/集群分享/zk选主算法.md)



## 脑裂问题

采用Quoroms投票的方式来选举Leader主要是为了解决“Split-Brain”问题。 <http://linux-ha.org/wiki/Split_Brain>

**Split-Brain问题说的是1个集群如果发生了网络故障，很可能出现1个集群分成了两部分，而这两个部分都不知道对方是否存活，不知道到底是网络问题还是直接机器down了，所以这两部分都要选举1个Leader，而一旦两部分都选出了Leader, 并且网络又恢复了，那么就会出现两个Brain的情况，整个集群的行为不一致了。**

所以集群要防止出现Split-Brain的问题出现，Quoroms是一种方式，即只有集群中超过半数节点投票才能选举出Leader。ZooKeeper默认采用了这种方式。更广义地解决Split-Brain的问题，一般有3种方式

1. Quorums


2. 采用Redundant communications，冗余通信的方式，集群中采用多种通信方式，防止一种通信方式失效导致集群中的节点无法通信。
3. Fencing, 共享资源的方式，比如能看到共享资源就表示在集群中，能够获得共享资源的锁的就是Leader，看不到共享资源的，就不在集群中

理解了Quorums就不难理解为什么集群中的节点数一般配置为奇数。节点数配置成奇数的集群的容忍度更高。

比如3个节点的集群，Quorums = 2, 也就是说集群可以容忍1个节点失效，这时候还能选举出1个lead，集群还可用

比如4个节点的集群，它的Quorums = 3，Quorums要超过3，相当于集群的容忍度还是1，如果2个节点失效，那么整个集群还是无效的

所以4个节点的集群的容忍度 = 3个节点的集群的容忍度，但是4个节点的集群多了1个节点，相当于浪费了资源。

更极端的例子是100个节点的集群，如果网络问题导致分为两个部分，50个节点和50个节点，这样整个集群还是不可用的，因为按照Quorums的方式必须51个节点才能保证选出1个Leader。这时候可以采用Weight加权的方式，有些节点的权值高，有些节点的权值低，最后计算权值，只要权值过半，也能选出1个Leader