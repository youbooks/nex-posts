## 1.配置

.zookeeper下载地址：<http://apache.mirrors.lucidnetworks.net/zookeeper/>

可以选择需要的版本，我下载的是zookeeper-3.4.6.tar.gz，解压得到文件夹zookeeper-3.4.3

由于手头机器不足，所以在一台机器上部署了3个server,如果你手头也比较紧，也可以这么做。

在主文件夹下建立一个zookeeper文件夹，在zookeeper文件夹里建立三个文件夹server1,server2,server3,

然后每个文件夹里面解压一个zookeeper的下载包，并且还建了几个文件夹，总体结构如下,最后那个是下载过来压缩包的解压文件

```
data,dataLog,logs,zookeeper-3.4.31
```

那么首先进入data目录，创建一个myid的文件，里面写入一个数字，比如我这个是server1,那么就写一个1，server2对应myid文件就写入2，server3对应myid文件就写个3 
然后进入zookeeper-3.4.3/conf目录，那么如果是刚下过来，会有3个文件，configuration.xml, log4j.properties,zoo_sample.cfg,这3个文件我们首先要做的就是在这个目录创建一个zoo.cfg的配置文件，当然你可以把zoo_sample.cfg文件改成zoo.cfg，打开zoo.cfg,文件内容如下：

```properties
# The number of milliseconds of each tick
tickTime=2000
# The number of ticks that the initial 
# synchronization phase can take
initLimit=10
# The number of ticks that can pass between 
# sending a request and getting an acknowledgement
syncLimit=5
# the directory where the snapshot is stored.
# do not use /tmp for storage, /tmp here is just 
# example sakes.
dataDir=/Users/zhaofeng/Documents/zookeeper-cluster/zoo2/data
dataLogDir=/Users/zhaofeng/Documents/zookeeper-cluster/zoo2/dataLog
# the port at which the clients will connect
clientPort=2181123456789101112131415
```

在文件末尾添加如下内容：

```properties
server.1=127.0.0.1:2888:3888
server.2=127.0.0.1:2889:3889
server.3=127.0.0.1:2890:3890
```

需要注意的是clientPort这个端口如果你是在1台机器上部署多个server,那么每台机器都要不同的clientPort，比如我server1是2181,server2是2182，server3是2183，dataDir和dataLogDir也需要区分下。

最后几行唯一需要注意的地方就是 server.X 这个数字就是对应 data/myid中的数字。你在3个server的myid文件中分别写入了1，2，3，那么每个server中的zoo.cfg都配server.1,server.2,server.3就OK了。因为在同一台机器上，后面连着的2个端口3个server都不要一样，否则端口冲突，其中第一个端口用来集群成员的信息交换，第二个端口是在leader挂掉时专门用来进行选举leader所用。

## 2.启动ZooKeeper伪机群的所有服务器

分别进入三个服务器的zookeeper-3.4.3/bin目录下，启动服务 
bin/zkServer.sh start 
启动完成后，查看服务器状态， 
bin/zkServer.sh status

> 一般来说，需要注意的是：按照zoo.cfg里面配置的顺序启动应用。同时需要注意查看zookeeper.out文件，如果出现连不上其他的端口号，很正常的事情。

错误如下：

```
Cannot open channel to 3 at election address /127.0.0.1:3890
java.net.ConnectException: Connection refused (Connection refused)
    at java.net.PlainSocketImpl.socketConnect(Native Method)
    at java.net.AbstractPlainSocketImpl.doConnect(AbstractPlainSocketImpl.java:350)
    at java.net.AbstractPlainSocketImpl.connectToAddress(AbstractPlainSocketImpl.java:206)
    at java.net.AbstractPlainSocketImpl.connect(AbstractPlainSocketImpl.java:188)
    at java.net.SocksSocketImpl.connect(SocksSocketImpl.java:392)
    at java.net.Socket.connect(Socket.java:589)
    at org.apache.zookeeper.server.quorum.QuorumCnxManager.connectOne(QuorumCnxManager.java:368)
    at org.apache.zookeeper.server.quorum.QuorumCnxManager.toSend(QuorumCnxManager.java:341)
    at org.apache.zookeeper.server.quorum.FastLeaderElection$Messenger$WorkerSender.process(FastLeaderElection.java:449)
    at org.apache.zookeeper.server.quorum.FastLeaderElection$Messenger$WorkerSender.run(FastLeaderElection.java:430)
    at java.lang.Thread.run(Thread.java:748)12345678910111213
```

等待所有的端口都启动完成后，就可以正常使用了。正常的标识是status返回的内容为

```
$ ./zkServer.sh status
JMX enabled by default
Using config: /Users/zhaofeng/Documents/zookeeper-cluster/zoo2/zookeeper-3.4.6/bin/../conf/zoo.cfg
Mode: leader1234
```

## 3.接入客户端

进入任意一个服务器的zookeeper/bin目录下，启动一个客户端，接入服务。

./zkCli.sh –server localhost:2181



## 4.扩容

http://siye1982.github.io/2015/06/16/zookeeper/