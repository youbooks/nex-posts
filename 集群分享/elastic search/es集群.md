

## 1.master和slave的职责

es中master节点相对没有mysql、zookeeper、redis等对于整个集群那么重要，但是也是特别重要的。es的master监控集群的拓扑结构和健康状态，分发索引分片到集群节点，不同的是具体文档的索引主分片不一定在master上。

 

## 2.选主算法以及如何避免脑裂

![](https://ws4.sinaimg.cn/large/006tKfTcgy1fq9t1ao6rmj31kw20m0yx.jpg)

form:

#### 代码跟踪:

![](https://ws1.sinaimg.cn/large/006tKfTcgy1fqfet8uuqmj31kw1ceq7v.jpg)



## elasticsearch更改node id生成方法

集群中节点的id是由discovery定义的，默认es有两种实现方式，一种是

org.elasticsearch.discovery.local.LocalDiscovery

表示把es的节点启动在同一个jvm的环境下，这样就可以通过AtomicLong来进行数字递增的id生成。

另一种是

org.elasticsearch.discovery.zen.ZenDiscovery

它是分布式环境下的节点发现机制，由于是分布式环境，数字递增形式比较难行得通，于是在zenDiscovery里面是使用64位的uuid来作为节点id。每次节点重启其id都是会变的，重新生成一个uuid，这与我的期望不符合，所以只能通过修改源码来解决。我这的需求就是每个节点必须有唯一的一个id，并且这个id不能变，考虑了下决定通过ip+端口的方式来作为节点的id，比如127.0.0.1:9300的节点id就是1270019300。下面是修改的源码。在ZenDiscovery这个类下

这样的话每次节点启动生成的nodeid都是一样的。



## 3.failover(请看演示)

当集群中只有一个节点在运行时，意味着会有一个单点故障问题——没有冗余。幸运的是，我们只需再启动一个节点即可防止数据丢失。

启动第二个节点

为了测试第二个节点启动后的情况，你可以在同一个目录内，完全依照启动第一个节点的方式来启动一个新节点（参考[[running-elasticsearch\]](https://github.com/elasticsearch-cn/elasticsearch-definitive-guide/blob/cn/020_Distributed_Cluster/20_Add_failover.asciidoc#running-elasticsearch)）。多个节点可以共享同一个目录。

当你在同一台机器上启动了第二个节点时，只要它和第一个节点有同样的 `cluster.name` 配置，它就会自动发现集群并加入到其中。但是在不同机器上启动节点的时候，为了加入到同一集群，你需要配置一个可连接到的单播主机列表。详细信息请查看[[unicast\]](https://github.com/elasticsearch-cn/elasticsearch-definitive-guide/blob/cn/020_Distributed_Cluster/20_Add_failover.asciidoc#unicast)

如果启动了第二个节点，我们的集群将会如[拥有两个节点的集群——所有主分片和副本分片都已被分配](https://github.com/elasticsearch-cn/elasticsearch-definitive-guide/blob/cn/020_Distributed_Cluster/20_Add_failover.asciidoc#cluster-two-nodes)所示。

Figure 1. 拥有两个节点的集群——所有主分片和副本分片都已被分配

当第二个节点加入到集群后，3个 *副本分片* 将会分配到这个节点上——每个主分片对应一个副本分片。这意味着当集群内任何一个节点出现问题时，我们的数据都完好无损。

所有新近被索引的文档都将会保存在主分片上，然后被并行的复制到对应的副本分片上。这就保证了我们既可以从主分片又可以从副本分片上获得文档。

`cluster-health` 现在展示的状态为 `green` ，这表示所有6个分片（包括3个主分片和3个副本分片）都在正常运行。

```
{
  "cluster_name": "elasticsearch",
  "status": "green", (1)
  "timed_out": false,
  "number_of_nodes": 2,
  "number_of_data_nodes": 2,
  "active_primary_shards": 3,
  "active_shards": 6,
  "relocating_shards": 0,
  "initializing_shards": 0,
  "unassigned_shards": 0,
  "delayed_unassigned_shards": 0,
  "number_of_pending_tasks": 0,
  "number_of_in_flight_fetch": 0,
  "task_max_waiting_in_queue_millis": 0,
  "active_shards_percent_as_number": 100
}
```

1. 集群 `status` 值为 `green` 。

我们的集群现在不仅仅是正常运行的，并且还处于 *始终可用* 的状态。

## 4.主从数据保持一致性的方法

配置文件:

```
action.write_consistency
```

consistency策略：

- 默认是quorum：公式int( (primary + number_of_replicas) / 2 ) + 1，过半节点都操作完成后返回
- one：一个主分片完成即可返回
- all：所有主分片和复制分片都操作完成返回