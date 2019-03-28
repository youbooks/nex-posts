### Apollo的config server对配置更新消息的处理

这里的"配置更新消息"指的是每一次发布release,Apollo都会记录一个ReleaseMessage在数据库中,并且会有一个定时线程池不断地去扫码单位时间内有无新的ReleaseMessage,代码如下:

```java
public class ReleaseMessageScanner implements InitializingBean {
  private static final Logger logger = LoggerFactory.getLogger(ReleaseMessageScanner.class);
  @Autowired
  private BizConfig bizConfig;
  @Autowired
  private ReleaseMessageRepository releaseMessageRepository;
  private int databaseScanInterval;
  private List<ReleaseMessageListener> listeners;
  private ScheduledExecutorService executorService;
  private long maxIdScanned;

  public ReleaseMessageScanner() {
    listeners = Lists.newCopyOnWriteArrayList();
    executorService = Executors.newScheduledThreadPool(1, ApolloThreadFactory
        .create("ReleaseMessageScanner", true));
  }

  @Override
  public void afterPropertiesSet() throws Exception {
    databaseScanInterval = bizConfig.releaseMessageScanIntervalInMilli();
      //初次扫码,需要查询到当前ReleaseMessage的最大Id
    maxIdScanned = loadLargestMessageId();
      //定时任务
    executorService.scheduleWithFixedDelay((Runnable) () -> {
      Transaction transaction = Tracer.newTransaction("Apollo.ReleaseMessageScanner", "scanMessage");
      try {
        scanMessages();
        transaction.setStatus(Transaction.SUCCESS);
      } catch (Throwable ex) {
        transaction.setStatus(ex);
        logger.error("Scan and send message failed", ex);
      } finally {
        transaction.complete();
      }
    }, databaseScanInterval, databaseScanInterval, TimeUnit.MILLISECONDS);

  }
```

```java
 /**
   * scan messages and send
   *
   * @return whether there are more messages
   */
  private boolean scanAndSendMessages() {
    //current batch is 500
    List<ReleaseMessage> releaseMessages =
        releaseMessageRepository.findFirst500ByIdGreaterThanOrderByIdAsc(maxIdScanned);
    if (CollectionUtils.isEmpty(releaseMessages)) {
      return false;
    }
      //如果查询到了满足条件的releaseMessage(大于当前maxId的记录),那么调用listner执行回调
    fireMessageScanned(releaseMessages);
    int messageScanned = releaseMessages.size();
    maxIdScanned = releaseMessages.get(messageScanned - 1).getId();
    return messageScanned == 500;
  }

......
    
    
     /**
   * Notify listeners with messages loaded
   * @param messages
   */
  private void fireMessageScanned(List<ReleaseMessage> messages) {
    for (ReleaseMessage message : messages) {
      for (ReleaseMessageListener listener : listeners) {
        try {
          listener.handleMessage(message, Topics.APOLLO_RELEASE_TOPIC);
        } catch (Throwable ex) {
          Tracer.logError(ex);
          logger.error("Failed to invoke message listener {}", listener.getClass(), ex);
        }
      }
    }
  }

```

NotificationControllerV2里面注册了一个listener:

```java
 @Override
  public void handleMessage(ReleaseMessage message, String channel) {
    logger.info("message received - channel: {}, message: {}", channel, message);

    String content = message.getMessage();
    Tracer.logEvent("Apollo.LongPoll.Messages", content);
    if (!Topics.APOLLO_RELEASE_TOPIC.equals(channel) || Strings.isNullOrEmpty(content)) {
      return;
    }

    String changedNamespace = retrieveNamespaceFromReleaseMessage.apply(content);

    if (Strings.isNullOrEmpty(changedNamespace)) {
      logger.error("message format invalid - {}", content);
      return;
    }

    if (!deferredResults.containsKey(content)) {
      return;
    }

    //create a new list to avoid ConcurrentModificationException
    List<DeferredResultWrapper> results = Lists.newArrayList(deferredResults.get(content));

    ApolloConfigNotification configNotification = new ApolloConfigNotification(changedNamespace, message.getId());
    configNotification.addMessage(content, message.getId());

    //do async notification if too many clients
      //如果client太多,那么异步设置每个DeferredResultWrapper的result
    if (results.size() > bizConfig.releaseMessageNotificationBatch()) {
      largeNotificationBatchExecutorService.submit(() -> {
        logger.debug("Async notify {} clients for key {} with batch {}", results.size(), content,
            bizConfig.releaseMessageNotificationBatch());
        for (int i = 0; i < results.size(); i++) {
            //满足条件就sleep一定的时间
          if (i > 0 && i % bizConfig.releaseMessageNotificationBatch() == 0) {
            try {
              TimeUnit.MILLISECONDS.sleep(bizConfig.releaseMessageNotificationBatchIntervalInMilli());
            } catch (InterruptedException e) {
              //ignore
            }
          }
          logger.debug("Async notify {}", results.get(i));
          results.get(i).setResult(configNotification);
        }
      });
      return;
    }

    logger.debug("Notify {} clients for key {}", results.size(), content);

    for (DeferredResultWrapper result : results) {
      result.setResult(configNotification);
    }
    logger.debug("Notification completed");
  }
```

那么,调用DeferredResult.setResult()有什么意义呢?DeferredResult又怎样的能力?

### DeferredResult

DeferredResult的作用,简单来讲就是释放容器线程,即请求过来不用阻塞,我们可以另起一个线程来异步处理耗时逻辑,但是不占用容器的线程,即容器还可以去处理别的请求

当异步线程调用eferredResult.setResult(),那么这时候才会真正地返回给请求发送者

DeferredResult和Callable有相似的作用,两者的对比请看:!!!理解Callable 和 Spring DeferredResult