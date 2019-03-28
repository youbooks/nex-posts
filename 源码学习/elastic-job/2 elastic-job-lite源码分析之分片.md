### zk上的分片node

如下三种情况都会触发主节点上的分片算法执行：

- 新的Job实例加入集群
- 现有的Job实例下线（如果下线的是leader节点，那么先选举然后触发分片算法的执行）
- 主节点选举

上述三种情况，会让zookeeper上leader节点的sharding节点上多出来一个necessary的临时节点，主节点每次执行Job前，都会去看一下这个节点，如果有则执行分片算法。

![](https://ws4.sinaimg.cn/large/006tNbRwgy1fx8t689vhzj309m02ya9u.jpg)



分片的执行结果会存储在zookeeper上，如下图，5个分片，每个分片应该由哪个Job实例来运行都已经分配好。分配的过程就是上面触发分片算法之后的操作。分配完成之后，各个Job实例就会在下次执行的时候使用上这个分配结果。

![](https://ws1.sinaimg.cn/large/006tNbRwgy1fx8t6dtl69j30eh07u745.jpg)





## 分片算法

所有的分片策略都继承JobShardingStrategy接口。根据当前注册到ZK的实例列表和在客户端配置的分片数量来进行数据分片。最终将每个Job实例应该获得的分片数字返回出去。 方法签名如下：

[![复制代码](https://common.cnblogs.com/images/copycode.gif)](javascript:void(0);)

```java
/**
     * 作业分片.
     * 
     * @param jobInstances 所有参与分片的单元列表
     * @param jobName 作业名称
     * @param shardingTotalCount 分片总数
     * @return 分片结果
     */
    Map<JobInstance, List<Integer>> sharding(List<JobInstance> jobInstances, String jobName, int shardingTotalCount);
```

[![复制代码](https://common.cnblogs.com/images/copycode.gif)](javascript:void(0);)

分片函数的触发，只会在leader选举的时候触发，也就是说只会在刚启动和leader节点离开的时候触发，并且是在leader节点上触发，而其他节点不会触发。

 

\1. 基于平均分配算法的分片策略

基于平均分配算法的分片策略对应的类是：AverageAllocationJobShardingStrategy。它是默认的分片策略。它的分片效果如下：

- 如果有3个Job实例, 分成9片, 则每个Job实例分到的分片是: 1=[0,1,2], 2=[3,4,5], 3=[6,7,8].
- 如果有3个Job实例, 分成8片, 则每个Job实例分到的分片是: 1=[0,1,6], 2=[2,3,7], 3=[4,5].
- 如果有3个Job实例, 分成10片, 则个Job实例分到的分片是: 1=[0,1,2,9], 2=[3,4,5], 3=[6,7,8].

 

\2. 作业名的哈希值奇偶数决定IP升降序算法的分片策略

这个策略的对应的类是：OdevitySortByNameJobShardingStrategy，它内部其实也是使用AverageAllocationJobShardingStrategy实现，只是在传入的节点实例顺序不一样，也就是上面接口参数的List<JobInstance>。AverageAllocationJobShardingStrategy的缺点是一旦分片数小于Job实例数，作业将永远分配至IP地址靠前的Job实例上，导致IP地址靠后的Job实例空闲。而OdevitySortByNameJobShardingStrategy则可以根据作业名称重新分配Job实例负载。如：

- 如果有3个Job实例，分成2片，作业名称的哈希值为奇数，则每个Job实例分到的分片是：1=[0], 2=[1], 3=[]
- 如果有3个Job实例，分成2片，作业名称的哈希值为偶数，则每个Job实例分到的分片是：3=[0], 2=[1], 1=[]

实现比较简单：

```java
long jobNameHash = jobName.hashCode();
if (0 == jobNameHash % 2) {
    Collections.reverse(jobInstances);
}
return averageAllocationJobShardingStrategy.sharding(jobInstances, jobName, shardingTotalCount);
```

 

\3. 根据作业名的哈希值对Job实例列表进行轮转的分片策略

这个策略的对应的类是：RotateServerByNameJobShardingStrategy，和上面介绍的策略一样，内部同样是用AverageAllocationJobShardingStrategy实现，也是在传入的List<JobInstance>列表顺序上做文章。

 

\4. 自定义分片策略

除了可以使用上述分片策略之外，elastic-job还允许自定义分片策略。我们可以自己实现JobShardingStrategy接口，并且配置到分片方法上去，整个过程比较简单，下面仅仅列出通过配置spring来切换自定义的分片算法的例子：

```java
<job:simple id="MyShardingJob1" class="nick.test.elasticjob.MyShardingJob1" registry-center-ref="regCenter" cron="0/10 * * * * ?" sharding-total-count="5" sharding-item-parameters="0=A,1=B,2=C,3=D,4=E" job-sharding-strategy-class="nick.test.elasticjob.MyJobShardingStrategy"/>
```

### 分片流程

io.elasticjob.lite.internal.sharding.ShardingService

```java
 /**
     * 判断是否需要重分片.
     * 
     * @return 是否需要重分片
     */
    public boolean isNeedSharding() {
        return jobNodeStorage.isJobNodeExisted(ShardingNode.NECESSARY);
    }

 /**
     * 如果需要分片且当前节点为主节点, 则作业分片.
     * 
     * <p>
     * 如果当前无可用节点则不分片.
     * </p>
     */
    public void shardingIfNecessary() {
        List<JobInstance> availableJobInstances = instanceService.getAvailableJobInstances();
        if (!isNeedSharding() || availableJobInstances.isEmpty()) {
            return;
        }
        if (!leaderService.isLeaderUntilBlock()) {
            blockUntilShardingCompleted();
            return;
        }
        waitingOtherShardingItemCompleted();
        LiteJobConfiguration liteJobConfig = configService.load(false);
        int shardingTotalCount = liteJobConfig.getTypeConfig().getCoreConfig().getShardingTotalCount();
        log.debug("Job '{}' sharding begin.", jobName);
        jobNodeStorage.fillEphemeralJobNode(ShardingNode.PROCESSING, "");
        resetShardingInfo(shardingTotalCount);
        JobShardingStrategy jobShardingStrategy = JobShardingStrategyFactory.getStrategy(liteJobConfig.getJobShardingStrategyClass());
        jobNodeStorage.executeInTransaction(new PersistShardingInfoTransactionExecutionCallback(jobShardingStrategy.sharding(availableJobInstances, jobName, shardingTotalCount)));
        log.debug("Job '{}' sharding complete.", jobName);
    }
```

分片后,在执行job前,实例需要去获取到对应的分片数字,这里有一个点需要注意,就是parameter

```java
    /**
     * 获取当前作业服务器分片上下文.
     * 
     * @param shardingItems 分片项
     * @return 分片上下文
     */
    public ShardingContexts getJobShardingContext(final List<Integer> shardingItems) {
        LiteJobConfiguration liteJobConfig = configService.load(false);
        removeRunningIfMonitorExecution(liteJobConfig.isMonitorExecution(), shardingItems);
        if (shardingItems.isEmpty()) {
            return new ShardingContexts(buildTaskId(liteJobConfig, shardingItems), liteJobConfig.getJobName(), liteJobConfig.getTypeConfig().getCoreConfig().getShardingTotalCount(), 
                    liteJobConfig.getTypeConfig().getCoreConfig().getJobParameter(), Collections.<Integer, String>emptyMap());
        }
        Map<Integer, String> shardingItemParameterMap = new ShardingItemParameters(liteJobConfig.getTypeConfig().getCoreConfig().getShardingItemParameters()).getMap();
        return new ShardingContexts(buildTaskId(liteJobConfig, shardingItems), liteJobConfig.getJobName(), liteJobConfig.getTypeConfig().getCoreConfig().getShardingTotalCount(), 
                liteJobConfig.getTypeConfig().getCoreConfig().getJobParameter(), getAssignedShardingItemParameterMap(shardingItems, shardingItemParameterMap));
    }

//@1
    private Map<Integer, String> getAssignedShardingItemParameterMap(final List<Integer> shardingItems, final Map<Integer, String> shardingItemParameterMap) {
        Map<Integer, String> result = new HashMap<>(shardingItemParameterMap.size(), 1);
        for (int each : shardingItems) {
            result.put(each, shardingItemParameterMap.get(each));
        }
        return result;
    }
```

@1:可以看到,在获取分片数字的过程中,作者给每个分片指定了其对应的parameter