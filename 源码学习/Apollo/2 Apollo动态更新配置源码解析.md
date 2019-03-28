## 1 客户端和config server保持长连接



### 1.1 Apollo对namespace在客户端的维护

```java
public class RemoteConfigRepository extends AbstractConfigRepository {
  private static final Logger logger = LoggerFactory.getLogger(RemoteConfigRepository.class);
  private static final Joiner STRING_JOINER = Joiner.on(ConfigConsts.CLUSTER_NAMESPACE_SEPARATOR);
  private static final Joiner.MapJoiner MAP_JOINER = Joiner.on("&").withKeyValueSeparator("=");
  private ConfigServiceLocator m_serviceLocator;
  private HttpUtil m_httpUtil;
  private ConfigUtil m_configUtil;
  private RemoteConfigLongPollService remoteConfigLongPollService;
  private volatile AtomicReference<ApolloConfig> m_configCache;
  private final String m_namespace;
  private final static ScheduledExecutorService m_executorService;
  private AtomicReference<ServiceDTO> m_longPollServiceDto;
  private AtomicReference<ApolloNotificationMessages> m_remoteMessages;
  private RateLimiter m_loadConfigRateLimiter;
  private AtomicBoolean m_configNeedForceRefresh;
  private SchedulePolicy m_loadConfigFailSchedulePolicy;
  private Gson gson;
  private static final Escaper pathEscaper = UrlEscapers.urlPathSegmentEscaper();
  private static final Escaper queryParamEscaper = UrlEscapers.urlFormParameterEscaper();

  static {
    m_executorService = Executors.newScheduledThreadPool(1,
        ApolloThreadFactory.create("RemoteConfigRepository", true));
  }

  /**
   * Constructor.
   *
   * @param namespace the namespace
   */
  public RemoteConfigRepository(String namespace) {
    m_namespace = namespace;
    m_configCache = new AtomicReference<>();
    m_configUtil = ApolloInjector.getInstance(ConfigUtil.class);
    m_httpUtil = ApolloInjector.getInstance(HttpUtil.class);
    m_serviceLocator = ApolloInjector.getInstance(ConfigServiceLocator.class);
    remoteConfigLongPollService = ApolloInjector.getInstance(RemoteConfigLongPollService.class);
    m_longPollServiceDto = new AtomicReference<>();
    m_remoteMessages = new AtomicReference<>();
    m_loadConfigRateLimiter = RateLimiter.create(m_configUtil.getLoadConfigQPS());
    m_configNeedForceRefresh = new AtomicBoolean(true);
    m_loadConfigFailSchedulePolicy = new ExponentialSchedulePolicy(m_configUtil.getOnErrorRetryInterval(),
        m_configUtil.getOnErrorRetryInterval() * 8);
    gson = new Gson();
    this.trySync();
    this.schedulePeriodicRefresh();
    this.scheduleLongPollingRefresh();
  }

```

RemoteConfigRepository这个类中,维护了一个namespace对应的配置,重要属性包括m_configCache(存储配置kv对),在初始化该类的时候,会调用trySync(),然后 this.scheduleLongPollingRefresh();

```java
  private void scheduleLongPollingRefresh() {
    remoteConfigLongPollService.submit(m_namespace, this);
  }
```

开始周期性地利用长连接和config server保持同步

### 1.2 长轮询

```java

  public boolean submit(String namespace, RemoteConfigRepository remoteConfigRepository) {
    boolean added = m_longPollNamespaces.put(namespace, remoteConfigRepository);
    m_notifications.putIfAbsent(namespace, INIT_NOTIFICATION_ID);
    if (!m_longPollStarted.get()) {
      startLongPolling();
    }
    return added;
  }

  private void startLongPolling() {
    if (!m_longPollStarted.compareAndSet(false, true)) {
      //already started
      return;
    }
    try {
      final String appId = m_configUtil.getAppId();
      final String cluster = m_configUtil.getCluster();
      final String dataCenter = m_configUtil.getDataCenter();
      final long longPollingInitialDelayInMills = m_configUtil.getLongPollingInitialDelayInMills();
      m_longPollingService.submit(new Runnable() {
        @Override
        public void run() {
          if (longPollingInitialDelayInMills > 0) {
            try {
              logger.debug("Long polling will start in {} ms.", longPollingInitialDelayInMills);
              TimeUnit.MILLISECONDS.sleep(longPollingInitialDelayInMills);
            } catch (InterruptedException e) {
              //ignore
            }
          }
          doLongPollingRefresh(appId, cluster, dataCenter);
        }
      });
    } catch (Throwable ex) {
      m_longPollStarted.set(false);
      ApolloConfigException exception =
          new ApolloConfigException("Schedule long polling refresh failed", ex);
      Tracer.logError(exception);
      logger.warn(ExceptionUtil.getDetailMessage(exception));
    }
  }

...
    
    
   private void doLongPollingRefresh(String appId, String cluster, String dataCenter) {
    final Random random = new Random();
    ServiceDTO lastServiceDto = null;
    //死循环
    while (!m_longPollingStopped.get() && !Thread.currentThread().isInterrupted()) {
        //rateLimiter限制频率
      if (!m_longPollRateLimiter.tryAcquire(5, TimeUnit.SECONDS)) {
        //wait at most 5 seconds
        try {
          TimeUnit.SECONDS.sleep(5);
        } catch (InterruptedException e) {
        }
      }
      Transaction transaction = Tracer.newTransaction("Apollo.ConfigService", "pollNotification");
      String url = null;
      try {
        if (lastServiceDto == null) {
          List<ServiceDTO> configServices = getConfigServices();
          lastServiceDto = configServices.get(random.nextInt(configServices.size()));
        }

        url =
            assembleLongPollRefreshUrl(lastServiceDto.getHomepageUrl(), appId, cluster, dataCenter,
                m_notifications);

        logger.debug("Long polling from {}", url);
        HttpRequest request = new HttpRequest(url);
        request.setReadTimeout(LONG_POLLING_READ_TIMEOUT);

        transaction.addData("Url", url);

        final HttpResponse<List<ApolloConfigNotification>> response =
            m_httpUtil.doGet(request, m_responseType);

        logger.debug("Long polling response: {}, url: {}", response.getStatusCode(), url);
        if (response.getStatusCode() == 200 && response.getBody() != null) {
          updateNotifications(response.getBody());
          updateRemoteNotifications(response.getBody());
          transaction.addData("Result", response.getBody().toString());
            //如果200,就只想notify方法
          notify(lastServiceDto, response.getBody());
        }

        //try to load balance
        if (response.getStatusCode() == 304 && random.nextBoolean()) {
          lastServiceDto = null;
        }

        m_longPollFailSchedulePolicyInSecond.success();
        transaction.addData("StatusCode", response.getStatusCode());
        transaction.setStatus(Transaction.SUCCESS);
      } catch (Throwable ex) {
        lastServiceDto = null;
        Tracer.logEvent("ApolloConfigException", ExceptionUtil.getDetailMessage(ex));
        transaction.setStatus(ex);
        long sleepTimeInSecond = m_longPollFailSchedulePolicyInSecond.fail();
        logger.warn(
            "Long polling failed, will retry in {} seconds. appId: {}, cluster: {}, namespaces: {}, long polling url: {}, reason: {}",
            sleepTimeInSecond, appId, cluster, assembleNamespaces(), url, ExceptionUtil.getDetailMessage(ex));
        try {
          TimeUnit.SECONDS.sleep(sleepTimeInSecond);
        } catch (InterruptedException ie) {
          //ignore
        }
      } finally {
        transaction.complete();
      }
    }
  }

```

```java
  private void notify(ServiceDTO lastServiceDto, List<ApolloConfigNotification> notifications) {
    if (notifications == null || notifications.isEmpty()) {
      return;
    }
    for (ApolloConfigNotification notification : notifications) {
      String namespaceName = notification.getNamespaceName();
      //create a new list to avoid ConcurrentModificationException
      List<RemoteConfigRepository> toBeNotified =
          Lists.newArrayList(m_longPollNamespaces.get(namespaceName));
      ApolloNotificationMessages originalMessages = m_remoteNotificationMessages.get(namespaceName);
      ApolloNotificationMessages remoteMessages = originalMessages == null ? null : originalMessages.clone();
      //since .properties are filtered out by default, so we need to check if there is any listener for it
      toBeNotified.addAll(m_longPollNamespaces
          .get(String.format("%s.%s", namespaceName, ConfigFileFormat.Properties.getValue())));
      for (RemoteConfigRepository remoteConfigRepository : toBeNotified) {
        try {
            //@1
          remoteConfigRepository.onLongPollNotified(lastServiceDto, remoteMessages);
        } catch (Throwable ex) {
          Tracer.logError(ex);
        }
      }
    }
  }
```

@1:执行回调

```java

  public void onLongPollNotified(ServiceDTO longPollNotifiedServiceDto, ApolloNotificationMessages remoteMessages) {
    m_longPollServiceDto.set(longPollNotifiedServiceDto);
    m_remoteMessages.set(remoteMessages);
    m_executorService.submit(new Runnable() {
      @Override
      public void run() {
        m_configNeedForceRefresh.set(true);
        trySync();
      }
    });
  }
```

可以看到,长连接返回到客户端, 通知客户端去TrySync

```java
  @Override
  protected synchronized void sync() {
    Transaction transaction = Tracer.newTransaction("Apollo.ConfigService", "syncRemoteConfig");

    try {
        //配置都是存在缓存中
      ApolloConfig previous = m_configCache.get();
        //同步,获取最新的配置
      ApolloConfig current = loadApolloConfig();

      //reference equals means HTTP 304
      if (previous != current) {
        logger.debug("Remote Config refreshed!");
          //更新配置
        m_configCache.set(current);
        this.fireRepositoryChange(m_namespace, this.getConfig());
      }

      if (current != null) {
        Tracer.logEvent(String.format("Apollo.Client.Configs.%s", current.getNamespaceName()),
            current.getReleaseKey());
      }

      transaction.setStatus(Transaction.SUCCESS);
    } catch (Throwable ex) {
      transaction.setStatus(ex);
      throw ex;
    } finally {
      transaction.complete();
    }
  }
```



## 2 config server 会延迟返回请求, 以构成长连接

```java

  @RequestMapping(method = RequestMethod.GET)
  public DeferredResult<ResponseEntity<List<ApolloConfigNotification>>> pollNotification(
      @RequestParam(value = "appId") String appId,
      @RequestParam(value = "cluster") String cluster,
      @RequestParam(value = "notifications") String notificationsAsString,
      @RequestParam(value = "dataCenter", required = false) String dataCenter,
      @RequestParam(value = "ip", required = false) String clientIp) {
    List<ApolloConfigNotification> notifications = null;

    try {
      notifications =
          gson.fromJson(notificationsAsString, notificationsTypeReference);
    } catch (Throwable ex) {
      Tracer.logError(ex);
    }

    if (CollectionUtils.isEmpty(notifications)) {
      throw new BadRequestException("Invalid format of notifications: " + notificationsAsString);
    }

    DeferredResultWrapper deferredResultWrapper = new DeferredResultWrapper();
    Set<String> namespaces = Sets.newHashSet();
    Map<String, Long> clientSideNotifications = Maps.newHashMap();
    Map<String, ApolloConfigNotification> filteredNotifications = filterNotifications(appId, notifications);

    for (Map.Entry<String, ApolloConfigNotification> notificationEntry : filteredNotifications.entrySet()) {
      String normalizedNamespace = notificationEntry.getKey();
      ApolloConfigNotification notification = notificationEntry.getValue();
      namespaces.add(normalizedNamespace);
        //构造clientSideNotifications,下面获取new notification要使用 @1
      clientSideNotifications.put(normalizedNamespace, notification.getNotificationId());
      if (!Objects.equals(notification.getNamespaceName(), normalizedNamespace)) {
        deferredResultWrapper.recordNamespaceNameNormalizedResult(notification.getNamespaceName(), normalizedNamespace);
      }
    }

    if (CollectionUtils.isEmpty(namespaces)) {
      throw new BadRequestException("Invalid format of notifications: " + notificationsAsString);
    }

    Multimap<String, String> watchedKeysMap =
        watchKeysUtil.assembleAllWatchKeys(appId, cluster, namespaces, dataCenter);

    Set<String> watchedKeys = Sets.newHashSet(watchedKeysMap.values());

    List<ReleaseMessage> latestReleaseMessages =
        releaseMessageService.findLatestReleaseMessagesGroupByMessages(watchedKeys);

    /**
     * Manually close the entity manager.
     * Since for async request, Spring won't do so until the request is finished,
     * which is unacceptable since we are doing long polling - means the db connection would be hold
     * for a very long time
     */
    entityManagerUtil.closeEntityManager();

      //获取新的notification @2
    List<ApolloConfigNotification> newNotifications =
        getApolloConfigNotifications(namespaces, clientSideNotifications, watchedKeysMap,
            latestReleaseMessages);

    if (!CollectionUtils.isEmpty(newNotifications)) {
        //如果有新的notification,那么deferredResultWrapper就可以填充result,然后会立马返回,不会挂起
      deferredResultWrapper.setResult(newNotifications);
    } else {
      deferredResultWrapper
          .onTimeout(() -> logWatchedKeys(watchedKeys, "Apollo.LongPoll.TimeOutKeys"));

      deferredResultWrapper.onCompletion(() -> {
        //unregister all keys
        for (String key : watchedKeys) {
          deferredResults.remove(key, deferredResultWrapper);
        }
        logWatchedKeys(watchedKeys, "Apollo.LongPoll.CompletedKeys");
      });

      //register all keys
      for (String key : watchedKeys) {
        this.deferredResults.put(key, deferredResultWrapper);
      }

      logWatchedKeys(watchedKeys, "Apollo.LongPoll.RegisteredKeys");
      logger.debug("Listening {} from appId: {}, cluster: {}, namespace: {}, datacenter: {}",
          watchedKeys, appId, cluster, namespaces, dataCenter);
    }

    return deferredResultWrapper.getResult();//@3
  }
  
```

@1,@2:客户端请求传过来的notifications其实是namespace和对应的releaseId的map, config server拿到后,用获取的最新的releaseId做对比,获取newNotification,如果不为空,那么就证明有新的配置更新

@3:hold住请求主要靠的是DeferredResult

关于Apollo怎么使用DeferredResult的,请阅读!!!3 Apollo源码解析之长轮询中config server干了什么



## 3 spring拿到的配置更新了吗?

为什么是问号呢?因为这个问题在我看源码的时候让我很疑惑,因为在上文中,我们看到Apollo只更新了Config对象

在上一篇文章中,我们解析了Apollo怎样把我们写好的配置注入到spring中,本文将解析当配置发生了改变时,客户端怎样应对

```java
public class ApolloApplicationContextInitializer implements
    ApplicationContextInitializer<ConfigurableApplicationContext> {
  private static final Logger logger = LoggerFactory.getLogger(ApolloApplicationContextInitializer.class);
  private static final Splitter NAMESPACE_SPLITTER = Splitter.on(",").omitEmptyStrings().trimResults();

  private final ConfigPropertySourceFactory configPropertySourceFactory = SpringInjector
      .getInstance(ConfigPropertySourceFactory.class);

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    ConfigurableEnvironment environment = context.getEnvironment();
    String enabled = environment.getProperty(PropertySourcesConstants.APOLLO_BOOTSTRAP_ENABLED, "false");
    if (!Boolean.valueOf(enabled)) {
      logger.debug("Apollo bootstrap config is not enabled for context {}, see property: ${{}}", context, PropertySourcesConstants.APOLLO_BOOTSTRAP_ENABLED);
      return;
    }
    logger.debug("Apollo bootstrap config is enabled for context {}", context);

    if (environment.getPropertySources().contains(PropertySourcesConstants.APOLLO_BOOTSTRAP_PROPERTY_SOURCE_NAME)) {
      //already initialized
      return;
    }

    String namespaces = environment.getProperty(PropertySourcesConstants.APOLLO_BOOTSTRAP_NAMESPACES, ConfigConsts.NAMESPACE_APPLICATION);
    logger.debug("Apollo bootstrap namespaces: {}", namespaces);
    List<String> namespaceList = NAMESPACE_SPLITTER.splitToList(namespaces);

    CompositePropertySource composite = new CompositePropertySource(PropertySourcesConstants.APOLLO_BOOTSTRAP_PROPERTY_SOURCE_NAME);
    for (String namespace : namespaceList) {
      Config config = ConfigService.getConfig(namespace);

      composite.addPropertySource(configPropertySourceFactory.getConfigPropertySource(namespace, config));
    }

    environment.getPropertySources().addFirst(composite);
  }
}

```

我们已经知道了,Apollo继承了ApplicationContextInitializer,在应用启动前完成了配置注入, 这里有一个细节,那就是

​      Config config = ConfigService.getConfig(namespace);

客户端配置更新的谜底就是这行代码,因为每个namespace对应的config其实是单例的,即是说,我们只需要更新config里面的属性就可以了,Apollo也正是这样做的,代码如下:

```java

  @Override
  public synchronized void onRepositoryChange(String namespace, Properties newProperties) {
    if (newProperties.equals(m_configProperties.get())) {
      return;
    }
    Properties newConfigProperties = new Properties();
    newConfigProperties.putAll(newProperties);

    Map<String, ConfigChange> actualChanges = updateAndCalcConfigChanges(newConfigProperties);

    //check double checked result
    if (actualChanges.isEmpty()) {
      return;
    }

    this.fireConfigChange(new ConfigChangeEvent(m_namespace, actualChanges));

    Tracer.logEvent("Apollo.Client.ConfigChanges", m_namespace);
  }


  private Map<String, ConfigChange> updateAndCalcConfigChanges(Properties newConfigProperties) {
    List<ConfigChange> configChanges =
        calcPropertyChanges(m_namespace, m_configProperties.get(), newConfigProperties);

    ImmutableMap.Builder<String, ConfigChange> actualChanges =
        new ImmutableMap.Builder<>();

    /** === Double check since DefaultConfig has multiple config sources ==== **/

    //1. use getProperty to update configChanges's old value
    for (ConfigChange change : configChanges) {
      change.setOldValue(this.getProperty(change.getPropertyName(), change.getOldValue()));
    }

    //2. update m_configProperties
    m_configProperties.set(newConfigProperties); //@1
    clearConfigCache();
      
      ...
```

@1:更新了config对象里面的m_configProperties属性,后面@Value的取值会从这个属性里面取

