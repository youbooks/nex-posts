## Name Server的启动

Name Server的启动, 首先通过调用NamesrvStartup类的main方法进行执行. 该类首先加载系统默认配置文件, NamesrvConfig和NettyServerConfig, 顾名思义NamesrcConfig就是Name Server相关的配置信息, 例如rocketmq的home目录等, NettyServerConfig就是启动Netty服务端时的相关配置信息, 例如监听端口, 工作线程池数, 服务端发送接收缓存池的大小等. 源代码如下:

```java
public static void main(String[] args) {
    main0(args);
}

public static NamesrvController main0(String[] args) {
    //设置RocketMQ版本号
    System.setProperty(RemotingCommand.REMOTING_VERSION_KEY, Integer.toString(MQVersion.CURRENT_VERSION));

    //设置数据发送缓冲区大小
    if (null == System.getProperty(NettySystemConfig.COM_ROCKETMQ_REMOTING_SOCKET_SNDBUF_SIZE)) {
        NettySystemConfig.socketSndbufSize = 4096;
    }

    //设置数据接收缓冲区大小
    if (null == System.getProperty(NettySystemConfig.COM_ROCKETMQ_REMOTING_SOCKET_RCVBUF_SIZE)) {
        NettySystemConfig.socketRcvbufSize = 4096;
    }

    try {
        //PackageConflictDetect.detectFastjson();

        Options options = ServerUtil.buildCommandlineOptions(new Options());
        commandLine =
            ServerUtil.parseCmdLine("mqnamesrv", args, buildCommandlineOptions(options),
                new PosixParser());
        if (null == commandLine) {
            System.exit(-1);
            return null;
        }

        //NameServer配置
        final NamesrvConfig namesrvConfig = new NamesrvConfig();
        //NettyServer配置
        final NettyServerConfig nettyServerConfig = new NettyServerConfig();
        nettyServerConfig.setListenPort(9876);
        if (commandLine.hasOption('c')) {
            String file = commandLine.getOptionValue('c');
            if (file != null) {
                InputStream in = new BufferedInputStream(new FileInputStream(file));
                properties = new Properties();
                properties.load(in);
                MixAll.properties2Object(properties, namesrvConfig);
                MixAll.properties2Object(properties, nettyServerConfig);

                namesrvConfig.setConfigStorePath(file);

                System.out.printf("load config properties file OK, " + file + "%n");
                in.close();
            }
        }

        if (commandLine.hasOption('p')) {
            MixAll.printObjectProperties(null, namesrvConfig);
            MixAll.printObjectProperties(null, nettyServerConfig);
            System.exit(0);
        }

        MixAll.properties2Object(ServerUtil.commandLine2Properties(commandLine), namesrvConfig);

        if (null == namesrvConfig.getRocketmqHome()) {
            System.out.printf("Please set the " + MixAll.ROCKETMQ_HOME_ENV
                + " variable in your environment to match the location of the RocketMQ installation%n");
            System.exit(-2);
        }

        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(lc);
        lc.reset();
        configurator.doConfigure(namesrvConfig.getRocketmqHome() + "/conf/logback_namesrv.xml");
        final Logger log = LoggerFactory.getLogger(LoggerName.NAMESRV_LOGGER_NAME);

        MixAll.printObjectProperties(log, namesrvConfig);
        MixAll.printObjectProperties(log, nettyServerConfig);

        //NamesrvController是Name Server真正的核心类
        final NamesrvController controller = new NamesrvController(namesrvConfig, nettyServerConfig);

        // remember all configs to prevent discard
        controller.getConfiguration().registerConfig(properties);

        //NamesrvController初始化
        boolean initResult = controller.initialize();
        if (!initResult) {
            controller.shutdown();
            System.exit(-3);
        }

        //添加java虚拟机关闭时的hook
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            private volatile boolean hasShutdown = false;
            private AtomicInteger shutdownTimes = new AtomicInteger(0);

            @Override
            public void run() {
                synchronized (this) {
                    log.info("shutdown hook was invoked, " + this.shutdownTimes.incrementAndGet());
                    if (!this.hasShutdown) {
                        this.hasShutdown = true;
                        long begineTime = System.currentTimeMillis();
                        controller.shutdown();
                        long consumingTimeTotal = System.currentTimeMillis() - begineTime;
                        log.info("shutdown hook over, consuming time total(ms): " + consumingTimeTotal);
                    }
                }
            }
        }, "ShutdownHook"));
        //NamesrvController启动
        controller.start();

        String tip = "The Name Server boot success. serializeType=" + RemotingCommand.getSerializeTypeConfigInThisServer();
        log.info(tip);
        System.out.printf(tip + "%n");

        return controller;
    } catch (Throwable e) {
        e.printStackTrace();
        System.exit(-1);
    }

    return null;
}
```

接下来看NamesrvController的initialize函数.

```java
public boolean initialize() {
    //kvConfigManager是nameserver里管理kv数据的一个组件
    this.kvConfigManager.load();
    //启动一个NettyRemotingServer实例
    this.remotingServer = new NettyRemotingServer(this.nettyServerConfig, this.brokerHousekeepingService);

    this.remotingExecutor =
        Executors.newFixedThreadPool(nettyServerConfig.getServerWorkerThreads(), new ThreadFactoryImpl("RemotingExecutorThread_"));
    //注册处理请求的processor
    this.registerProcessor();

    this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

        @Override
        public void run() {
            NamesrvController.this.routeInfoManager.scanNotActiveBroker();
        }
    }, 5, 10, TimeUnit.SECONDS);

    this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

        @Override
        public void run() {
            NamesrvController.this.kvConfigManager.printAllPeriodically();
        }
    }, 1, 10, TimeUnit.MINUTES);

    return true;
}
private void registerProcessor() {
    if (namesrvConfig.isClusterTest()) {
        //ClusterTestRequestProcessor与DefaultRequestProcessor的区别在于ClusterTestRequestProcessor无法从本地读取路由信息时会从集群中读取
        this.remotingServer.registerDefaultProcessor(new ClusterTestRequestProcessor(this, namesrvConfig.getProductEnvName()),
            this.remotingExecutor);
    } else {

        this.remotingServer.registerDefaultProcessor(new DefaultRequestProcessor(this), this.remotingExecutor);
    }
}
```

NamesrvController的initialize过程主要就是初始化一个通信组件中的NettyRemotingServer实例
 NamesrvController的start函数非常简单就是启动定义好的NettyRemotingServer.

```java
public void start() throws Exception {
    this.remotingServer.start();
}
```

NettyRemotingServer启动后就可以接收请求了, 其中最主要的请求就是处理路由信息相关的请求, 例如broker的注册请求.

## 路由信息的管理


 NamesrvController在创建时, 实例化了RouteInfoManager和BrokerHouseKeepingService两个对象. Name Server中最重要的就是RouteInfoManager类. Name Server所有的Topic和Borker信息都保存在RouteInfoManager中, RouteInfoManager保存所有的路由信息. Netty服务端接收到请求后, 回调请求处理程序DefaultRequestProcessor, defaultRequestProcessor根据请求类型RequestCode, 例如注册Broker或者新建Topic请求, 来更新RouteInfoManager路由信息.
 先看注册Broker的源码:

```java
public RegisterBrokerResult registerBroker(
    final String clusterName,
    final String brokerAddr,
    final String brokerName,
    final long brokerId,
    final String haServerAddr,
    final TopicConfigSerializeWrapper topicConfigWrapper,
    final List<String> filterServerList,
    final Channel channel) {
    RegisterBrokerResult result = new RegisterBrokerResult();
    try {
        try {
            //加锁
            this.lock.writeLock().lockInterruptibly();
            //根据clusterName获取brokerNames
            Set<String> brokerNames = this.clusterAddrTable.get(clusterName);
            if (null == brokerNames) {
                brokerNames = new HashSet<String>();
                this.clusterAddrTable.put(clusterName, brokerNames);
            }
            //brokerNames中增加当前注册的brokerName
            brokerNames.add(brokerName);

            //是否首次注册的标记
            boolean registerFirst = false;

            //根据brokerName获取brokerData
            BrokerData brokerData = this.brokerAddrTable.get(brokerName);
            if (null == brokerData) {
                registerFirst = true;
                brokerData = new BrokerData();
                brokerData.setBrokerName(brokerName);
                HashMap<Long, String> brokerAddrs = new HashMap<Long, String>();
                brokerData.setBrokerAddrs(brokerAddrs);

                this.brokerAddrTable.put(brokerName, brokerData);
            }
            String oldAddr = brokerData.getBrokerAddrs().put(brokerId, brokerAddr);
            registerFirst = registerFirst || (null == oldAddr);

            //如果topicConfigWrapper不为空, 并且发送请求的broker是master, 创建或者新建topicConfig
            if (null != topicConfigWrapper //
                && MixAll.MASTER_ID == brokerId) {
                if (this.isBrokerTopicConfigChanged(brokerAddr, topicConfigWrapper.getDataVersion())//
                    || registerFirst) {
                    ConcurrentHashMap<String, TopicConfig> tcTable =
                        topicConfigWrapper.getTopicConfigTable();
                    if (tcTable != null) {
                        for (Map.Entry<String, TopicConfig> entry : tcTable.entrySet()) {
                            //根据topicConfigWrapper中携带的topicConfig跟新本地保存的topicConfig信息
                            this.createAndUpdateQueueData(brokerName, entry.getValue());
                        }
                    }
                }
            }

            //更新broker存活信息
            BrokerLiveInfo prevBrokerLiveInfo = this.brokerLiveTable.put(brokerAddr,
                new BrokerLiveInfo(
                    System.currentTimeMillis(),
                    topicConfigWrapper.getDataVersion(),
                    channel,
                    haServerAddr));
            if (null == prevBrokerLiveInfo) {
                log.info("new broker registerd, {} HAServer: {}", brokerAddr, haServerAddr);
            }

            if (filterServerList != null) {
                if (filterServerList.isEmpty()) {
                    this.filterServerTable.remove(brokerAddr);
                } else {
                    this.filterServerTable.put(brokerAddr, filterServerList);
                }
            }

            //如果发送请求的不是master, 读取master的HaServerAddr和MasterAddr, 并放入返回结果中
            if (MixAll.MASTER_ID != brokerId) {
                String masterAddr = brokerData.getBrokerAddrs().get(MixAll.MASTER_ID);
                if (masterAddr != null) {
                    BrokerLiveInfo brokerLiveInfo = this.brokerLiveTable.get(masterAddr);
                    if (brokerLiveInfo != null) {
                        result.setHaServerAddr(brokerLiveInfo.getHaServerAddr());
                        result.setMasterAddr(masterAddr);
                    }
                }
            }
        } finally {
            this.lock.writeLock().unlock();
        }
    } catch (Exception e) {
        log.error("registerBroker Exception", e);
    }

    return result;
}
```

简单来说,整个过程就是将brokerName和对应的addr保存到本地, 如果注册信息中同时包含了topicConfig信息, 并且发送请求的是master, 则更新topicConfig信息.

## 心跳检查

RocketMQ使用BrokerHouseKeepingService来处理broker是否存活. 如果broker失效, 异常或者关闭, 则将broker从RouteInfoManager路由信息中移除, 同时将与该broker相关的topic信息也一起删除. Netty服务端专门启动了一个线程用于监听管道的失效, 异常或者关闭等的事件队列, 当事件队列里面有新事件时, 则取出事件并判断事件的类型, 然后调用BrokerHouseKeepingService对应的方法来处理该事件.
 NamesrvController初始化时会实例化一个BrokerHouseKeepingService对象.

```java
public NamesrvController(NamesrvConfig namesrvConfig, NettyServerConfig nettyServerConfig) {
    this.namesrvConfig = namesrvConfig;
    this.nettyServerConfig = nettyServerConfig;
    this.kvConfigManager = new KVConfigManager(this);
    this.routeInfoManager = new RouteInfoManager();
    //实例化一个BrokerHouseKeepingService对象
    this.brokerHousekeepingService = new BrokerHousekeepingService(this);
    this.configuration = new Configuration(
        log,
        this.namesrvConfig, this.nettyServerConfig
    );
    this.configuration.setStorePathFromConfig(this.namesrvConfig, "configStorePath");
}
```

BrokerHousekeepingService实现了ChannelEventListener接口, 并且NettyRemotingServer启动时会启动BrokerHousekeepingService(细节参考[RocketMQ源码阅读(二)-通信模块](https://www.jianshu.com/p/cf0f41314a76)).

```java
//类NettyRemotingServer中
if (this.channelEventListener != null) {
    this.nettyEventExecuter.start();
}
```

BrokerHousekeepingService会对连接事件, 连接关闭事件, 异常事件,闲置事件进行处理.

```java
//BrokerHousekeepingService
@Override
public void onChannelConnect(String remoteAddr, Channel channel) {
}

@Override
public void onChannelClose(String remoteAddr, Channel channel) {
    this.namesrvController.getRouteInfoManager().onChannelDestroy(remoteAddr, channel);
}

@Override
public void onChannelException(String remoteAddr, Channel channel) {
    this.namesrvController.getRouteInfoManager().onChannelDestroy(remoteAddr, channel);
}

@Override
public void onChannelIdle(String remoteAddr, Channel channel) {
    this.namesrvController.getRouteInfoManager().onChannelDestroy(remoteAddr, channel);
}
```

所有事件调用的都是用一个函数, 接下来看一下RouteInfoManager的onChannelDestroy方法. 该方法的主要功能就是清理brokerInfo, filterServer和Queue中关于丢失连接的broker的信息. 代码如下:

```java
public void onChannelDestroy(String remoteAddr, Channel channel) {
    String brokerAddrFound = null;
    //根据channel寻找brokerAddrFound
    if (channel != null) {
        try {
            try {
                this.lock.readLock().lockInterruptibly();
                Iterator<Entry<String, BrokerLiveInfo>> itBrokerLiveTable =
                    this.brokerLiveTable.entrySet().iterator();
                while (itBrokerLiveTable.hasNext()) {
                    Entry<String, BrokerLiveInfo> entry = itBrokerLiveTable.next();
                    if (entry.getValue().getChannel() == channel) {
                        brokerAddrFound = entry.getKey();
                        break;
                    }
                }
            } finally {
                this.lock.readLock().unlock();
            }
        } catch (Exception e) {
            log.error("onChannelDestroy Exception", e);
        }
    }
    //无法根据channel找到就使用传入的remoteAddr参数
    if (null == brokerAddrFound) {
        brokerAddrFound = remoteAddr;
    } else {
        log.info("the broker's channel destroyed, {}, clean it's data structure at once", brokerAddrFound);
    }

    if (brokerAddrFound != null && brokerAddrFound.length() > 0) {

        try {
            try {
                this.lock.writeLock().lockInterruptibly();
                //移除brokerLiveTable中的信息
                this.brokerLiveTable.remove(brokerAddrFound);
                //移除filterServerTable中的信息
                this.filterServerTable.remove(brokerAddrFound);
                String brokerNameFound = null;
                boolean removeBrokerName = false;
                Iterator<Entry<String, BrokerData>> itBrokerAddrTable =
                    this.brokerAddrTable.entrySet().iterator();
                //根据brokerAddrFound寻找brokerName
                while (itBrokerAddrTable.hasNext() && (null == brokerNameFound)) {
                    BrokerData brokerData = itBrokerAddrTable.next().getValue();

                    Iterator<Entry<Long, String>> it = brokerData.getBrokerAddrs().entrySet().iterator();
                    while (it.hasNext()) {
                        Entry<Long, String> entry = it.next();
                        Long brokerId = entry.getKey();
                        String brokerAddr = entry.getValue();
                        if (brokerAddr.equals(brokerAddrFound)) {
                            brokerNameFound = brokerData.getBrokerName();
                            it.remove();
                            log.info("remove brokerAddr[{}, {}] from brokerAddrTable, because channel destroyed",
                                brokerId, brokerAddr);
                            break;
                        }
                    }

                    if (brokerData.getBrokerAddrs().isEmpty()) {
                        //如果对应的brokerName再没有别的节点(即失去连接的节点是该brokerName拥有的唯一节点), 需要移除brokerName的信息
                        removeBrokerName = true;
                        itBrokerAddrTable.remove();
                        log.info("remove brokerName[{}] from brokerAddrTable, because channel destroyed",
                            brokerData.getBrokerName());
                    }
                }

                //移除brokerName的信息,在对应的cluster移除brokerName
                if (brokerNameFound != null && removeBrokerName) {
                    Iterator<Entry<String, Set<String>>> it = this.clusterAddrTable.entrySet().iterator();
                    while (it.hasNext()) {
                        Entry<String, Set<String>> entry = it.next();
                        String clusterName = entry.getKey();
                        Set<String> brokerNames = entry.getValue();
                        boolean removed = brokerNames.remove(brokerNameFound);
                        if (removed) {
                            log.info("remove brokerName[{}], clusterName[{}] from clusterAddrTable, because channel destroyed",
                                brokerNameFound, clusterName);

                            //对应的cluster中再没有别的brokerName, 移除cluster
                            if (brokerNames.isEmpty()) {
                                log.info("remove the clusterName[{}] from clusterAddrTable, because channel destroyed and no broker in this cluster",
                                    clusterName);
                                it.remove();
                            }

                            break;
                        }
                    }
                }

                //移除brokerName对应的queueData
                if (removeBrokerName) {
                    Iterator<Entry<String, List<QueueData>>> itTopicQueueTable =
                        this.topicQueueTable.entrySet().iterator();
                    while (itTopicQueueTable.hasNext()) {
                        Entry<String, List<QueueData>> entry = itTopicQueueTable.next();
                        String topic = entry.getKey();
                        List<QueueData> queueDataList = entry.getValue();

                        Iterator<QueueData> itQueueData = queueDataList.iterator();
                        while (itQueueData.hasNext()) {
                            QueueData queueData = itQueueData.next();
                            if (queueData.getBrokerName().equals(brokerNameFound)) {
                                itQueueData.remove();
                                log.info("remove topic[{} {}], from topicQueueTable, because channel destroyed",
                                    topic, queueData);
                            }
                        }

                        //如果对应的topicQueue下再没有别的queueData, topicQueue
                        if (queueDataList.isEmpty()) {
                            itTopicQueueTable.remove();
                            log.info("remove topic[{}] all queue, from topicQueueTable, because channel destroyed",
                                topic);
                        }
                    }
                }
            } finally {
                this.lock.writeLock().unlock();
            }
        } catch (Exception e) {
            log.error("onChannelDestroy Exception", e);
        }
    }
}
```

清理信息的过程没有什么难点, 只是细节比较多.