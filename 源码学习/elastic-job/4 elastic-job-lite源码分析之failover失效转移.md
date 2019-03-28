## 失效转移触发条件

elastic-job的任务配置有个failover，如果开启设置为true的时候，会启动真正的失效转移：，elastic-job的任务又两个配置failover（默认值为false）和monitorExecution（默认值是true）。只有对monitorExecution为true的情况下才可以开启失效转移。

[![](https://ws1.sinaimg.cn/large/006tNbRwgy1fx8vh9euujj30jj021glg.jpg)](http://images2015.cnblogs.com/blog/15700/201706/15700-20170623075011054-834736149.png)

所谓失效转移，就是在执行任务的过程中遇见异常的情况，这个分片任务可以在其他节点再次执行。这个和上面的HA不同，对于HA，上面如果任务终止，那么不会在其他任务实例上再次重新执行。

Job的失效转移监听来源于FailoverListenerManager中JobCrashedJobListener的dataChanged方法。FailoverListenerManager监听的是zk的instance节点删除事件。如果任务配置了failover等于true，其中某个instance与zk失去联系或被删除，并且失效的节点又不是本身，就会触发失效转移逻辑。

io.elasticjob.lite.internal.failover.FailoverListenerManager

```java
   class JobCrashedJobListener extends AbstractJobListener {
        
        @Override
        protected void dataChanged(final String path, final Type eventType, final String data) {
            if (isFailoverEnabled() && Type.NODE_REMOVED == eventType && instanceNode.isInstancePath(path)) {
                String jobInstanceId = path.substring(instanceNode.getInstanceFullPath().length() + 1);
                if (jobInstanceId.equals(JobRegistry.getInstance().getJobInstance(jobName).getJobInstanceId())) {
                    return;
                }
                List<Integer> failoverItems = failoverService.getFailoverItems(jobInstanceId);
                if (!failoverItems.isEmpty()) {
                    for (int each : failoverItems) {
                        failoverService.setCrashedFailoverFlag(each);
                        failoverService.failoverIfNecessary();
                    }
                } else {
                    for (int each : shardingService.getShardingItems(jobInstanceId)) {
                        failoverService.setCrashedFailoverFlag(each);
                        failoverService.failoverIfNecessary();
                    }
                }
            }
        }
    }
    
```

在某个任务实例失效时，elastic-job会在leader节点下面创建failover节点以及items节点。items节点下会有失效任务实例的原本应该做的分片好。比如，失效的任务实例原来负责分片1和2。那么items节点下就会有名字叫1的子节点，就代表分片1需要转移到其他节点上去运行。如下图：

![](https://ws4.sinaimg.cn/large/006tNbRwgy1fx8vdvvszyj30df09nmx2.jpg)

## 处理failover

由于每个存活着的任务实例都会收到zk节点丢失的事件，哪个分片失效也已经在leader节点的failover子节点下。所以这些或者的任务实例就会争抢这个分片任务来执行。为了保证不重复执行，elastic-job使用了curator的LeaderLatch类来进行选举执行。在获得执行权后，就会在sharding节点的分片上添加failover节点，并写上任务实例，表示这个故障任务迁移到某一个任务实例上去完成。如下图中的sharding节点上的分片1：

[![](https://ws1.sinaimg.cn/large/006tNbRwgy1fx8veo9thcj30c30cat8m.jpg)

](http://images2015.cnblogs.com/blog/15700/201706/15700-20170623075012538-625661285.png)

然后在 job执行前,  会专门去检查是否需要failover, 如果需要, 那么就获取本实例"抢到的"failover的分片(如果没抢到,当然就就正常执行job),代码如下:

```java
    @Override
    public ShardingContexts getShardingContexts() {
        boolean isFailover = configService.load(true).isFailover();
        if (isFailover) {
            List<Integer> failoverShardingItems = failoverService.getLocalFailoverItems();
            if (!failoverShardingItems.isEmpty()) {
                return executionContextService.getJobShardingContext(failoverShardingItems);
            }
        }
        shardingService.shardingIfNecessary();
        List<Integer> shardingItems = shardingService.getLocalShardingItems();
        if (isFailover) {
            shardingItems.removeAll(failoverService.getLocalTakeOffItems());
        }
        shardingItems.removeAll(executionService.getDisabledItems(shardingItems));
        return executionContextService.getJobShardingContext(shardingItems);
    }
   
```



