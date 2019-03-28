## 前言

对一个分布式的项目来说,节点间的通讯是重要的一块,我们也发现一般都会使用TCP来做,自定义协议,然后拿到报文后转为自定义的请求类

对于Zookeeper来说,存在两种网络关系:

- 服务端对客户端
- 服务端各节点之间

##服务端对客户端

### 请求封装

zk将客户端和服务端相互通信的请求都抽象为一个Request对象,这个request对象会在之后在各个Processor中传递,这个我们将在下一篇文章中讲解

```java
public class Request {
    private static final Logger LOG = LoggerFactory.getLogger(Request.class);

    public final static Request requestOfDeath = new Request(null, 0, 0, 0,
            null, null);

    public Request(ServerCnxn cnxn, long sessionId, int xid, int type,
            ByteBuffer bb, List<Id> authInfo) {
        this.cnxn = cnxn;
        this.sessionId = sessionId;
        this.cxid = xid;
        this.type = type;
        this.request = bb;
        this.authInfo = authInfo;
    }
    ...
```

![](https://ws4.sinaimg.cn/large/006tNc79gy1fyt6pti01cj318k0p8433.jpg)

那么request对象是从何而来?

### zk中的连接

zookeeper中使用ServerCnxn代表一个从客户端到服务端的连接,用ServerCnxnFactory这样一个工厂来管理这些连接

两者都有两种实现,分别利用了NIO和Netty的IO特点. 在ServerCnxn中,完成了从tcp中的二进制报文到java里面的request对象的转化

### NIOServerCnxn









## 服务端各节点之间

### 请求封装

```java
public class QuorumPacket implements Record {
  private int type;
  private long zxid;
  private byte[] data;
  private java.util.List<org.apache.zookeeper.data.Id> authinfo;
```

![](https://ws2.sinaimg.cn/large/006tNc79gy1fyt6qj70vtj315m0p0whu.jpg)

### 