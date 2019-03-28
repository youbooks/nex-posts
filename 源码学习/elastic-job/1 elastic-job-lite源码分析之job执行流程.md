## 作业实例

先看作业接口

```java
/**
 * 简单分布式作业接口.
 * 
 * @author zhangliang
 */
public interface SimpleJob extends ElasticJob {
    
    /**
     * 执行作业.
     *
     * @param shardingContext 分片上下文
     */
    void execute(ShardingContext shardingContext);
}
```

### 一个作业开发的示例:

```java
public class MyElasticJob implements SimpleJob {
    
    @Override
    public void execute(ShardingContext context) {
        switch (context.getShardingItem()) {
            case 0: 
                // do something by sharding item 0
                break;
            case 1: 
                // do something by sharding item 1
                break;
            case 2: 
                // do something by sharding item 2
                break;
            // case n: ...
        }
    }
}
```

可以看到,这里面最重要的是这个ShardingContext,提供了当前实例对应的分片

```java
/**
 * 分片上下文.
 * 
 * @author zhangliang
 */
@Getter
@ToString
public final class ShardingContext {
    
    /**
     * 作业名称.
     */
    private final String jobName;
    
    /**
     * 作业任务ID.
     */
    private final String taskId;
    
    /**
     * 分片总数.
     */
    private final int shardingTotalCount;
    
    /**
     * 作业自定义参数.
     * 可以配置多个相同的作业, 但是用不同的参数作为不同的调度实例.
     */
    private final String jobParameter;
    
    /**
     * 分配于本作业实例的分片项.
     */
    private final int shardingItem;
    
    /**
     * 分配于本作业实例的分片参数.
     */
    private final String shardingParameter;
    
    public ShardingContext(final ShardingContexts shardingContexts, final int shardingItem) {
        jobName = shardingContexts.getJobName();
        taskId = shardingContexts.getTaskId();
        shardingTotalCount = shardingContexts.getShardingTotalCount();
        jobParameter = shardingContexts.getJobParameter();
        this.shardingItem = shardingItem;
        shardingParameter = shardingContexts.getShardingItemParameters().get(shardingItem);
    }
}
```

## 作业执行流程分析

elastic-job 有一个作业执行器用来执行作业

io.elasticjob.lite.executor.AbstractElasticJobExecutor

```java
/**
     * 执行作业.
     */
    public final void execute() {
        try {
            jobFacade.checkJobExecutionEnvironment();
        } catch (final JobExecutionEnvironmentException cause) {
            jobExceptionHandler.handleException(jobName, cause);
        }
        //从jobFacade取分片上下文
        ShardingContexts shardingContexts = jobFacade.getShardingContexts();
        if (shardingContexts.isAllowSendJobEvent()) {
            jobFacade.postJobStatusTraceEvent(shardingContexts.getTaskId(), State.TASK_STAGING, String.format("Job '%s' execute begin.", jobName));
        }
        //检测是否有错过执行的分片
        if (jobFacade.misfireIfRunning(shardingContexts.getShardingItemParameters().keySet())) {
            if (shardingContexts.isAllowSendJobEvent()) {
                jobFacade.postJobStatusTraceEvent(shardingContexts.getTaskId(), State.TASK_FINISHED, String.format(
                        "Previous job '%s' - shardingItems '%s' is still running, misfired job will start after previous job completed.", jobName, 
                        shardingContexts.getShardingItemParameters().keySet()));
            }
            return;
        }
        try {
            jobFacade.beforeJobExecuted(shardingContexts);
            //CHECKSTYLE:OFF
        } catch (final Throwable cause) {
            //CHECKSTYLE:ON
            jobExceptionHandler.handleException(jobName, cause);
        }
        //执行
        execute(shardingContexts, JobExecutionEvent.ExecutionSource.NORMAL_TRIGGER);
        while (jobFacade.isExecuteMisfired(shardingContexts.getShardingItemParameters().keySet())) {
            jobFacade.clearMisfire(shardingContexts.getShardingItemParameters().keySet());
            execute(shardingContexts, JobExecutionEvent.ExecutionSource.MISFIRE);
        }
        //判断是否需要failover(failover后面会有单独的文章来解析)
        jobFacade.failoverIfNecessary();
        try {
            jobFacade.afterJobExecuted(shardingContexts);
            //CHECKSTYLE:OFF
        } catch (final Throwable cause) {
            //CHECKSTYLE:ON
            jobExceptionHandler.handleException(jobName, cause);
        }
    }
```

然后是AbstractElasticJobExecutor的具体的执行过程

```java
private void process(final ShardingContexts shardingContexts, final JobExecutionEvent.ExecutionSource executionSource) {
    //@1 首先获取分片上下文里面的shardingItemParameters,它是一个map,key是分片值,value是对应的parameter
        Collection<Integer> items = shardingContexts.getShardingItemParameters().keySet();
        if (1 == items.size()) {
            int item = shardingContexts.getShardingItemParameters().keySet().iterator().next();
            JobExecutionEvent jobExecutionEvent =  new JobExecutionEvent(shardingContexts.getTaskId(), jobName, executionSource, item);
            process(shardingContexts, item, jobExecutionEvent);
            return;
        }
    //如果只有一个分片,那么直接执行即可,如果有多个,那么就需要使用CountDownLatch来处理@1
        final CountDownLatch latch = new CountDownLatch(items.size());
        for (final int each : items) {
            final JobExecutionEvent jobExecutionEvent = new JobExecutionEvent(shardingContexts.getTaskId(), jobName, executionSource, each);
            if (executorService.isShutdown()) {
                return;
            }
            executorService.submit(new Runnable() {
                
                @Override
                public void run() {
                    try {
                        process(shardingContexts, each, jobExecutionEvent);
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        try {
            latch.await();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
}
```

@1:CountDownLatch这个类能够使一个线程等待其他线程完成各自的工作后再执行。例如，应用程序的主线程希望在负责启动框架服务的线程已经启动所有的框架服务之后再执行。

在这里elastic job让所有分片都处理完(每一个分片都会执行simpleJob.execute(shardingContext);),才走下面的流程

这样保证了ElasticJobExecutor 的一次process,会让所有的分片都处理完job

也就是当下一个时间点ElasticJobExecutor需要process,而上一次process还没处理完,就会阻塞住,因为每个job的ElasticJobExecutor也是单例的