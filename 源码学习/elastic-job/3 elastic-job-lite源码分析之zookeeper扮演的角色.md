elastic job之所以能做到分布式的调度,主要是依赖zookeeper

通过设计zk的树状文件结构,让每一个实例都能实时地获取配置,感知变化

### zookeeper的事件类型

org.apache.curator.framework.recipes.cache.TreeCacheEvent

```java
    public static enum Type {
        NODE_ADDED,//添加节点
        NODE_UPDATED,//更新节点
        NODE_REMOVED,//移除节点
        CONNECTION_SUSPENDED,
        CONNECTION_RECONNECTED,
        CONNECTION_LOST,
        INITIALIZED;

        private Type() {
        }
    }
```





注册了很多监听器,用来响应zookeeper中节点的变化

```java
    class ShardingTotalCountChangedJobListener extends AbstractJobListener {
        
        @Override
        protected void dataChanged(final String path, final Type eventType, final String data) {
            if (configNode.isConfigPath(path) && 0 != JobRegistry.getInstance().getCurrentShardingTotalCount(jobName)) {
                int newShardingTotalCount = LiteJobConfigurationGsonFactory.fromJson(data).getTypeConfig().getCoreConfig().getShardingTotalCount();
                if (newShardingTotalCount != JobRegistry.getInstance().getCurrentShardingTotalCount(jobName)) {
                    shardingService.setReshardingFlag();
                    JobRegistry.getInstance().setCurrentShardingTotalCount(jobName, newShardingTotalCount);
                }
            }
        }
    }
    
```



```java
   
    class ListenServersChangedJobListener extends AbstractJobListener {
        
        @Override
        protected void dataChanged(final String path, final Type eventType, final String data) {
            if (!JobRegistry.getInstance().isShutdown(jobName) && (isInstanceChange(eventType, path) || isServerChange(path))) {
                shardingService.setReshardingFlag();
            }
        }
        
        private boolean isInstanceChange(final Type eventType, final String path) {
            return instanceNode.isInstancePath(path) && Type.NODE_UPDATED != eventType;
        }
        
        private boolean isServerChange(final String path) {
            return serverNode.isServerPath(path);
        }
    }
```



## 主节点选举监听

#### 主节点选举监听:

```java

    
    class LeaderElectionJobListener extends AbstractJobListener {
        
        @Override
        protected void dataChanged(final String path, final Type eventType, final String data) {
            if (!JobRegistry.getInstance().isShutdown(jobName) && (isActiveElection(path, data) || isPassiveElection(path, eventType))) {
                leaderService.electLeader();
            }
        }
        
        //如果当前没有节点 且 localServer可用
        private boolean isActiveElection(final String path, final String data) {
            return !leaderService.hasLeader() && isLocalServerEnabled(path, data);
        }
        
        //如果主节点挂了 且 作业服务器可用
        private boolean isPassiveElection(final String path, final Type eventType) {
            return isLeaderCrashed(path, eventType) && serverService.isAvailableServer(JobRegistry.getInstance().getJobInstance(jobName).getIp());
        }
        
        //通过NODE_REMOVED事件来判断主节点是否挂掉
        private boolean isLeaderCrashed(final String path, final Type eventType) {
            return leaderNode.isLeaderInstancePath(path) && Type.NODE_REMOVED == eventType;
        }
        
        private boolean isLocalServerEnabled(final String path, final String data) {
            return serverNode.isLocalServerPath(path) && !ServerStatus.DISABLED.name().equals(data);
        }
    }

```

#### 主节点"辞职"监听:

```java
 class LeaderAbdicationJobListener extends AbstractJobListener {
        
        @Override
        protected void dataChanged(final String path, final Type eventType, final String data) {
            if (leaderService.isLeader() && isLocalServerDisabled(path, data)) {
                leaderService.removeLeader();
            }
        }
        
     //如果当前节点状态变为 disabled 且 当前节点是主节点
        private boolean isLocalServerDisabled(final String path, final String data) {
            return serverNode.isLocalServerPath(path) && ServerStatus.DISABLED.name().equals(data);
        }
    }
```



## 主节点选择逻辑

```java
   /**
     * 选举主节点.
     */
    public void electLeader() {
        log.debug("Elect a new leader now.");
        jobNodeStorage.executeInLeader(LeaderNode.LATCH, new LeaderElectionExecutionCallback());
        log.debug("Leader election completed.");
    }
    

    @RequiredArgsConstructor
    class LeaderElectionExecutionCallback implements LeaderExecutionCallback {
        
        @Override
        public void execute() {
            if (!hasLeader()) {
                jobNodeStorage.fillEphemeralJobNode(LeaderNode.INSTANCE, JobRegistry.getInstance().getJobInstance(jobName).getJobInstanceId());
            }
        }
    }
```

Ephemeral:adj.临时的