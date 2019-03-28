## 环境

OS:macOs

java version:1.8.0_121

## NameServer

在NamesrvStartup.java里面的

```java
        if (null == namesrvConfig.getRocketmqHome()) {
            System.out.printf("Please set the %s variable in your environment to match the location of the RocketMQ installation%n", MixAll.ROCKETMQ_HOME_ENV);
            System.exit(-2);

        }
```

前面加一句：

```java
namesrvConfig.setRocketmqHome("/Users/wangwang/Repo/dealmoon/rocketmq/home");
```
![](https://ws4.sinaimg.cn/large/006tNbRwgy1fxa027dbbdj31fh0u0gnz.jpg)s

然后启动NamesrvStartup.java里面的main方法



## Broker

在

```java

if (null == brokerConfig.getRocketmqHome()) {
    System.out.printf("Please set the " + MixAll.ROCKETMQ_HOME_ENV
        + " variable in your environment to match the location of the RocketMQ installation");
    System.exit(-2);
}
```


前面添加以下内容

```java
brokerConfig.setRocketmqHome("/Users/wangwang/Repo/dealmoon/rocketmq/home");
```

![](https://ws1.sinaimg.cn/large/006tNbRwgy1fxa02msuzyj31c50u00v1.jpg)

然后启动BrokerStartup.java里面的main方法