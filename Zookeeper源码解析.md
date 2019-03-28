



## 1.zk介绍

## 2. 服务端启动流程

## 3. zk的网络模型之—quorum之间的通信

在每个zk的服务端中,会维护一个QuorumCnxManager来管理peer之间的通信,下面官方给的注释其实非常详细

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0gtendmrjj31bk0ma1kx.jpg)

翻译一下:

- 1.这个类实现了一个连接管理器,目的是使用TCP来实现leader选举.它为每对server都维护了一个连接. Tricky的部分是我们保证了每队server之间都恰好只有一个连接(这个tricky part后面会重点介绍)
- 2.对每一个peer,这个管理器都维护了一个用来发送的消息队列.如果有任意的peer的连接断掉,那么sender线程会把未发送的消息返回内存中(list)

### 3.1 主动连接

在开始leader选举的时候,首先会连接所有配置的peer

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0f5ckhkn8j30u207sab5.jpg)

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0gtf79elkj30wk0bawkh.jpg)

queueSendMap被用来存储待发送的消息,key是每个peer的sid,value是一个ArrayBlockingQueue,储存着未发送的ByteBuffer

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0f5ict0xmj314i0pmn2y.jpg)

- 1.新建socket(所以说是用TCP来做的网络通讯),然后connect到配置的节点的electionAddr

- 2.如果需要做saslAuth,因为这个权限验证需要耗时,为了防止阻塞,使用异步来做连接,否则同步.来看看两者的区别:

  ​	同步的:

  ​	直接执行startConnection()开启连接
   ![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0gtfqyflmj31he0awk0p.jpg)

  ​	![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0gse5frezj31g0148n75.jpg)

   - 1.注意,这里其实我们已经和peer建立了socket连接,也就是说对应的节点将会收到这个connection,这时候我们做的第一件事,就是发送我们的myid给对方
   - 2.如果对方的sid大于本节点的sid,那么久close掉这个socket,然后让这个节点来连本节点
   - 3.4.如果对方的sid小于本节点的sid,那么新建SendWorker和RecvWorker,并且存储sendworker到senderWorkerMap中,新建一个阻塞队列并存储到queueSendMap中

  这里我们需要注意的是:本节点只会主动连接sid更小的peer,而放弃sid更大的节点的socket

  ​	异步的:

  ​	会将连接逻辑包成一个QuorumConnectionReqThread类,然后放在一个单独的线程池里面执行
  ![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0gs8b10f5j31oa0nkn39.jpg)

### 3.2 接收连接

承接上文,有其他节点会连接本节点并发送sid过来,QuorumCnxManager使用Listener来接收其他节点的连接.Listener是一个线程,它维护着QuorumCnxManager的serverSocket,在它的run()方法中,使用一个死循环来接收连接事件

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0gtaes1a9j30su0bctav.jpg)

如果有连接事件,那么进入receiveConnection()方法,这里和前面一样,如果有权限验证就走异步,我们就暂且只看同步的:
receiveConnection()会调用handleConnection():

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0gtjueuxsj31h816g46u.jpg)

如果接收到的对方节点的sid小于本节点sid,那么关闭这个socket,然后主动去连接它(connectOne())

否则接收这个连接,为它生成SendWorker和RecvWorker

### 3.3 tricky part

刚看源码的时候,奇怪为什么在主动连接和接收连接的时候,会有sid之间的对比,并舍弃一部分socket. 其实这是为了保持一对节点(a pair of servers)在一个QuorumCnxManager中只维持一个socket

我们假设现在没有sid的比较和舍弃,那么下图三个节点互相主动连接和接收连接:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0gtpm01qfj30wu0iaq6n.jpg)
以Peer1来看,它的ServerSocket接收了两个socket,它又主动连了两个socket,也就是说0和1之间,1和2之间,在Peer1中均有两个socket,而这两socket都能完成消息的收发,所以它们是重复的

为了解决这个问题,QuorumCnxManager设定了规则:

> 两个节点,sid大的一方主动连小的一方,sid小的一方接收大的一方的连接

如图所示,这样保证了对于一对节点,每个节点都只有一个socket来完成通信

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0gtbdz6ruj30n40mwte0.jpg)

### 3.4 发送消息和接收消息

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0gtvmzxsbj30p406wjse.jpg)

SendWorker顾名思义,承担了发送消息的活儿,它是一个内部类,也是一个线程,每个节点对应一个SendWorker,在它的run()方法中维护这一段loop:
![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0gu0pzoyej31fk0r079i.jpg)

会先从queueSendMap拿出这个节点对应的消息队列,然后poll消息出来,如果不为空,就发送,逻辑很简单
![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0gu3jumjqj30x40e8q5f.jpg)
而发送也很简单,就是把byteBuffer转化为byte数组写入DataOutputStream,由这个DataOutputStream写入到socket中

而接收消息的活儿由RecvWorker承担,它的数据结构和逻辑基本和SendWorker大同小异,最终会把接收到的消息写入到recvQueue中,由于和SendWorker基本相同,所以这里就不再赘述


## 4. ZAB之一---集群选主算法

### 4.1 一句话搞懂FastLeaderElection

快速选主算法其实非常简单,如果用一句话来概括的话,就是:所有节点互相通信,弱节点"臣服于"强节点,直到所有弱节点都被强节点"征服",而强弱的判别方法就是totalOrderPredicate,在下面会重点介绍

### 4.2 源码分析

### 4.2.1 属性介绍

每个zk节点在选举的时候,会维护一个Proposal,一个Proposal由五个属性定义(zk本身没有Proposal这个数据结构,但是用到了这个概念,这四个属性实质上都是属于FLE类的)

- 1.self(QuorumPeer)

  主要是用到了self的myid,其实也是配置中所谓的sid(serverId)

  ![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0bskhyik5j30o801874f.jpg)

  配置文件示例:

  ![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0bsmnm97pj30ty086gn9.jpg)

- 2.proposedLeader

  提议的leader的myid

- 3.proposedZxid

  zxid其实可以理解为txid,即事务id,一个zxid表示一个zk事务

  zxid的构成:

  ![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0bsp0keeuj30y2034gm8.jpg)

  它是一个长度[64位](https://www.baidu.com/s?wd=64%E4%BD%8D&tn=24004469_oem_dg&rsv_dl=gh_pl_sl_csd)的数字，其中低32位是按照数字递增，即每次客户端发起一个proposal，低32位的数字简单加1。高32位是leader周期的epoch编号。

  在开始选举的时候,会去获取最后一个被存储的zxid

  ![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0gtcjjgdmj30xk05gdkq.jpg)

  ![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0bstaxbg4j30se06a3zg.jpg)

  如图所示,如果zkDb未被初始化,会先初始化zkDb(从本地文件中加载数据到内存中,构成zk独有的用作存储的数据结构),然后读取最近的一个zxid

- 4.proposedEpoch

  epoch翻译过来是"纪元", 其实它表示群首序列号，每当老的群首崩溃，新群首的 epoch 递增自老群首 epoch。所以即使同一个服务器，先后两次当选成群首，两次 epoch 也是不一样的。

- 5.logicalclock

  翻译过来是逻辑时钟,代表选举的轮次

### 4.2.2 选举流程

我们知道在quorumPeer的run()方法(QuorumPeer本身是一个Thread)中,维持着一个最重要的loop

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0f32cdbqej31hw0vyaht.jpg)

在这个loop中,不论何时state为LOOKING的时候,都会使用LEStrategy去寻找leader(为什么这里要说不论何时满足state=LOOKING,因为leader是很有可能宕机的,这时候follower的followLeader()会报错,然后shutdown掉follower,重置此peer的状态为LOOKING,然后peer就会重新走到上图的逻辑,来继续寻找leader,这时候一般会重新选举出一个新的leader)


lookForLeader()是FastLeaderElection(一下以FLE代替)的入口方法


所有的逻辑都蕴藏在这段loop里面


如果我们确定一个竞争规则,例如跑的更快的人获胜成为leader,那么无论怎么比,跑得快的人终将成为leader

对于zk的FLE算法也是一样,先规定一个方法用来判断两两较量中谁赢,比赛结束后输家被迫变成赢家,然后又去和别人比,经过一轮又一轮的较量,所有输家最终都会变成同一个赢家













## 5.ZAB之二---选举后的新建epoch和同步

### 5.1 新建epoch
在这个阶段被新选举的leader需要确保之前的leader不能再提交新的Proposals,所以需要和Followers约定一个新的peerEpoch,代表一轮新的纪元(peerEpoch和electionEpoch的区别是:前者是选举后的事务处理的纪元,后者是选举的轮次)

在选举完成后,被选为Leader的节点状态变为LEADING,并新建Leader对象,开始进行lead():
![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0heh80mhkj30xa0i6ju3.jpg)

在lead()函数中,最开始的两个步骤是:1.新建LearnerCnxAcceptor,顾名思义这个类接收Follower的连接并为每一个Follower建立LearnerHandler 2. getEpochToPropose()阻塞,直到超过半数的peer接受了新的peerEpoch, 而之所以会阻塞,是因为要等待足够的来自Follower的ACKEPOCH,而要得到ACKEPOCH,LearnerHandler会先处理来自Follower的FOLLOWERINFO,然后发送LEADERINFO,再然后才能收到ACKEPOCH
用一张流程图来概括这个过程:
![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0gu9a5b3fj31ia15s7ew.jpg)

这个过程我们来详细解析源码的实现:

对于Leader对象:
![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0hervbtcej31lm0ren4y.jpg)

- 1.新建LearnerCnxAcceptor,接收Follower的连接,并为每个Follower新建LearnerHandler
	![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0heuq7xnqj31co0oq0ye.jpg)
- 2.新建epoch(阻塞方法,直到收到过半数的ACKEPOCH)
- 3.使用新的epoch新建新zxid,之后的所有的事务都从这个zxid开始累加

对于Follower对象:
在选举完成后,被选为Follower的节点状态变为FOLLOWING,并新建FOLLOWER对象,开始进行followLeader():
![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0hewfav2pj30s20e277j.jpg)

在followLeader()函数中:
![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0heztsrw4j31sc0vqwpf.jpg)

- 1.连接Leader,这时候LearnerCnxAcceptor起作用,接收Follower的连接,并为这个Follower新建LearnerHandler
- 2.注册到Leader,这个方法其实是接受来自Leader的新epoch
- 3.同步Leader,达到数据的一致性,这个会在下一章讲到
- 4.注册和同步都完成,就一个死循环来接受和处理来自Leader的消息

来看registerWithLeader():
![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0hfvm70cfj31yq13k18q.jpg)
- 1.从前面的调用可知pktType为FOLLOWERINFO,构造QuorumPacket,传入pktType以及新生成的zxid(注意这里,其实我们要传的是acceptedEpoch,但是qp没有epoch的属性,所以需要转为zxid,然后leader收到后再从zxid中获取epoch),然后发送给Leader
- 2.收到LEADERINFO,替换掉当前的acceptedEpoch
	acceptedEpoch和currentEpoch的区别:
	![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0hgcus9vyj318a05ata4.jpg)
	换句话说,当Follower收到NEWLEADER时(代表主从同步完成),currentEpoch才被acceptedEpoch替换
- 3.重置acceptedEpoch后,发送ACKEPOCH
- 4.返回由newEpoch生成的新zxid,之后的所有的事务都从这个zxid开始累加

对于LearnerHandler对象:
![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0hi4ljtsaj31lg18ak4l.jpg)

可以看到,首先接收FOLLOWERINFO,如果不是会报错,然后调用leader的getEpochToPropose()来获取新的peerEpoch(前面得知leader的lead()方法也会调用getEpochToPropose()并阻塞)
![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0hi6pm41bj31p40x4qc9.jpg)
用一个hashSet做了同步,避免了并发造成epoch的值得错误
把sid放入connectingFollowers中,判断接收的Follower的lastAcceptedEpoch是否比当前的epoch大或者相等,如果是就重置当前epoch
然后判断connectingFollowers的size是否达到所有peer总数的一半,如果没达到那么wait(),timeout设置为initLimit*tickTime(这俩只都是配置文件中的,默认值是10和2000,也就是最多等待20s),释放掉锁后让其他的LearnerHandler来调用,但是最终返回通过半数的epoch(否则就抛出超时异常)
所以最后方法的返回:
- 1.如果leader的epoch>所有Follower的lastAcceptedEpoch,那么返回leader的epoch
- 2.如果leader的epoch<=任意Follower的lastAcceptedEpoch,那么最终返回最大的lastAcceptedEpoch+1
最后会设置leader的currentEpoch为这个新的epoch

继续:
![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0hiqyj0q4j321o0skk1j.jpg)
LearnerHandler会发送LEADERINFO并等待ACKEPOCH(waitForEpochAck)
![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0hj26v2huj31po0zi7e3.jpg)
可以看到waitForEpochAck()和getEpochToPropose()大同小异,都是不满足verifier.containsQuorum()就阻塞,直到别的handler满足后notifyAll()

走到这里ZAB协议中的新建peerEpoch阶段就走完了



### 5.2 leader和follower数据同步

新建peerEpoch之后代表peer之间可以开始新"纪元"了,但是这不代表集群这时候就可用,因为此时还有极大可能存在peer间数据的不一致. 为什么?举例比如集群崩溃后,原先存在未超过半数ack的Proposal会在shutdown()中被写入到原Leader的zkDatabase中,强制进行提交(这一点会在后文中重点解析),重新选主后原Leader无论是否成为新的Leader,节点间都产生了数据不一致

而同步数据的方式主要是通过Leader和Follower之间的lastZxid的对比:

- 如果Follower的zxid更大,那么做修建(TRUNC)

- 如果更小但仍然大于Leader的minCommittedLog,那么判定为DIFF,Leader会将每条Follower缺失的Proposal通过发送COMMIT消息的方式,让Follower写入到ZKDatabase中
- 如果更小且小于Leader的minCommittedLog,判定为SNAP,Leader直接把此时地snap序列化后发送给Follower

#### 5.2.1 先看Leader的代码:

前提: 在上面的registerWithLeader()的最后发送ACKEPOCH的时候带上了Follower的lastLoggedZxid给到Leader,所以对比依据来源于此

Leader还是使用LearnerHandler来处理,并且仍然发生在run()中:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0s8aawoufj31pg1v2e12.jpg)

- 1.获取控制committedLog读写的可重入锁

- 2.peerLastZxid和dataTree的lastProcessedZxid相等,则发送DIFF
  注意: 1).dataTree的lastProcessedZxid其实是等于maxCommittedLog的,这一点在fastForwardFromEdits()中通过listener回调实现,后面会着重展开
           2).为什么相等了还发送DIFF?因为无论是何判定结果,最后都会调用leader.startForwarding()来同步outstandingProposals和toBeApplied,这俩集合分别存储的是还未广播的Proposal和已写入txnLog但还未收到过半ack的Proposal

- 3.peerLastZxid在maxCommittedLog和minCommittedLog之间, 轮询内存中的commitedLog集合,遇到大于peerLastZxid的,发送COMMIT消息给Follower让Follower提交这条事务消息

- 4.peerLastZxid太大了,大过maxCommittedLog,那么发送TRUNC,让Follower自己进行"修建"

- 5.peerLastZxid太小,比minCommittedLog还小,则是SNAP

  ```java
  if (packetToSend == Leader.SNAP) {
      // Dump data to peer
      leader.zk.getZKDatabase().serializeSnapshot(oa);
      oa.writeString("BenWasHere", "signature");
  }
  bufferedOutput.flush();
  ```

  LearnerHandler直接发送被序列化的dataTree给到peer

#### 5.2.1 Follower中的逻辑:

Follower的同步逻辑发生在syncWithLeader()中

![image-20190305214525700](/Users/wangwang/Library/Application Support/typora-user-images/image-20190305214525700.png)

- 1.如果是DIFF,则不需要snapshot了
- 2.如果是SNAP,那么发序列化Leader的snapshot并覆盖当前的
- 3.如果是TRUNC,则进行"修建"取出多余的,并重置lastProcessedZxid



Leader发送的COMMIT消息都会被放入packetNotCommitted集合中,通过下面的代码进行log和commit:

![](https://ww1.sinaimg.cn/large/007iUjdily1g0s90hjlixj31ao0a2tb8.jpg)



#### 5.2.2 NEWLEADER和UPTODATE

![](https://ww1.sinaimg.cn/large/007iUjdily1g0s8nflbbcj318a07q762.jpg)

LearnerHandler在对比并发生完DIFF(包括若干COMMIT消息) 或 TRUNC 或 SNAP消息后,发送NEWLEADER给到Follower

Follower收到NEWLEADER后:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0s8srwip5j31tk0lmjy9.jpg)

1.在快照文件的目录下,生成updatingEpoch文件
2.生成快照,并**设置currentEpoch为newEpoch**(即上一节产生的新的peerEpoch,之前只重置了acceptedEpoch,直到此时才更新currentEpoch,代表真正接受了新的轮次)
3.发送ACK回执



```java
leader.waitForNewLeaderAck(getSid(), qp.getZxid(), getLearnerType());
```

Leader会一直等待ack直到满足quorum:

```java
queuedPackets.add(new QuorumPacket(Leader.UPTODATE, -1, null, null));
```

收到过半peer发送的ack后,Leader发送UPTODATE消息

![image-20190305213050868](/Users/wangwang/Library/Application Support/typora-user-images/image-20190305213050868.png)

Follower收到UPTODATE,代表数据同步完成,集群正式可用,这里也通过outerLoop关键字退出死循环



画图小结主从同步的过程:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0fhd9u9gkj30ie0dytab.jpg)

#### 


![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0guac2ejjj31521iik2d.jpg)


## 6.ZAB之三---事务消息和广播

>ZAB 是 ZooKeeper Atomic Broadcast （ZooKeeper 原子广播协议）的缩写，它是特别为 ZooKeeper 设计的崩溃可恢复的原子消息广播算法。ZooKeeper 使用 **Leader** 群首服务器来接收并处理所有事务请求，并采用 ZAB 协议，将服务器数据的状态变更以**事务** Proposal 的形式广播到**所有**的 Follower 服务器上去。这种主备模型架构保证了同一时刻集群中**只有一个**服务器广播服务器的状态变更，因此能够很好的处理客户端大量的并发请求。

我们知道所谓分布式事务,两阶段提交(2PC)是一种解决方案,zk的原子广播协议就类似于2PC,过程和步骤如下:

- 1.群首服务器 Leader 在接收到来自客户端的Request并写入txnLog后,向**所有** Follower 发送一个 Proposal 消息，假设为 p;

- 2.当一个 Follower 服务器接收到消息 p 并写入txnLog后，会响应一个 ACK 消息，通知群首它已经接受该 Proposal;

- 3.当收到 **仲裁数量** 的服务器发送的 ACK 消息后（该仲裁数包括群首自己），群首就会写入数据到dataTree然后发送 Commit 消息给所有 Follower 更新状态。

### 6.1 processor处理链路
接下来我们以一个实际的写请求来串联起整个处理链路
假设有一个来自cilentA(它和Leader直接相连,如果不是则会由Follower转发,这个我们后面再分析)的写请求:

#### 6.1.1 进入processor链路之前

ServerCnxn的selector调用select()方法,选择到可读的Channel并使用socket.read()读取到数据,然后调用ZookeeperServer.processPacket()处理读取到的ByteBuffer

```java
public void processPacket(ServerCnxn cnxn, ByteBuffer incomingBuffer) {
        InputStream bais = new ByteBufferInputStream(incomingBuffer);
        BinaryInputArchive bia = BinaryInputArchive.getArchive(bais);
        RequestHeader h = new RequestHeader();
        h.deserialize(bia, "header");
    ......
        Request si = new Request(cnxn, cnxn.getSessionId(), h.getXid(),
                  h.getType(), incomingBuffer, cnxn.getAuthInfo());
        si.setOwner(ServerCnxn.me);
        submitRequest(si);
}
```

可以看到在这里zk封装了一个Request对象,使用这个类在Processor链中传递.关于client和server之间,以及server内部的数据结构变化我总结了一张图:

![](https://ww1.sinaimg.cn/large/007iUjdily1g0k0lr21buj32w00pwk24.jpg)

```java
public void submitRequest(Request si) {
    ......
    firstProcessor.processRequest(si);
    ......
}
```

可以看到从这里进入到processor的调用链,所以首先我们需要看看Processor的结构以及它是怎样被初始化的:

```java
public interface RequestProcessor {
    @SuppressWarnings("serial")
    public static class RequestProcessorException extends Exception {
        public RequestProcessorException(String msg, Throwable t) {
            super(msg, t);
        }
    }

    void processRequest(Request request) throws RequestProcessorException;

    void shutdown();
}
```

在processRequest()中做具体的处理逻辑,通常Processor都有一个nextProcessor属性,指向下一个节点的Processor

在ZookeeperServer类的startUp()方法中,会调用setupRequestProcessors()用来初始化各个Processor—LeaderZookeeperServer和FollowerZookeeperServer的实现是不同的:

![](https://ww1.sinaimg.cn/large/007iUjdily1g0k0ytznc5j31jg0i4ahn.jpg)

在创建Processor的时候,基本都是会把nextProcessor传入构造方法,除了个别的(如FinalRequestProcessor,因为它是最末一环,不需要nextProcessor),这段代码其实已经展现了调用链路,总结起来就是:

![](https://ww1.sinaimg.cn/large/007iUjdily1g0k110rif5j312i10safk.jpg)

(FollowerZookeeper的实现之后再分析)

#### 6.1.2 PreRequestProcessor

依然是老套路,维护一个BlockingQueue,在run()中依次处理,首先会调用pRequest()
![](https://ww1.sinaimg.cn/large/007iUjdily1g0k56hzfm7j31ms0aon02.jpg)
client传来的byteBuffer中主要包含两个个对象:RequestHeader和CreateRequest,分别用来存储Request的type以及path和data,所以这里新建一个CreateRequest,然后在pRequest2Txn()做解析,只有事务请求才会调用pRequest2Txn()

![](https://ww1.sinaimg.cn/large/007iUjdily1g0k5b7qd0oj30r00o242n.jpg)

来看pRequest2Txn():

```java
protected void pRequest2Txn(int type, long zxid, Request request, Record record, boolean deserialize){
     request.hdr = new TxnHeader(request.sessionId, request.cxid, zxid,
                                    Time.currentWallTime(), type);
     switch (type) {
            case OpCode.create:                
                zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
                CreateRequest createRequest = (CreateRequest)record;   
                if(deserialize)
                    ByteBufferInputStream.byteBuffer2Record(request.request, createRequest);
                String path = createRequest.getPath();
                ......
                request.txn = new CreateTxn(path, createRequest.getData(), listACL,
                        createMode.isEphemeral(), newCversion);
     ......
}
```

主要逻辑其实就是为Request的TxnHeader,Txn这两个对象赋值,通过读取CreateRequest中的path和data构造了CreateTxn对象

注意:只有事务请求才会调用pRequest2Txn(),只有事务请求Request才有TxnHeader

#### 6.1.3 ProposalRequestProcessor

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0kq98sihmj319a0h4gqb.jpg)

- 1.所有请求都会传递到下一个Processor,即CommitProcessor处理
- 2.只有事务请求Request才有TxnHeader,所以只有事务消息会调用leader.propose()和sycProcessor,分别做广播和持久化

先来看leader.propose():

![](https://ww1.sinaimg.cn/large/007iUjdily1g0kqfyl9p8j31r813kwo7.jpg)

- 1.先将Request中的hdr和txn序列化并转为byteArray(我们知道只有事务消息这俩属性才不为空)
  然后构造QuorumPacket,代表在QuorumPeer之间发送的消息,属性有消息类型,zxid,和被序列化的Request中的hdr和txn,然后调用sendPacket()发送

- 2.构造Proposal对象,并存入outstandingProposals集合里面,key是zxid,value是Proposal对象. 这里请注意zk会使用不同的容器来存放不同阶段的事务消息,这里的outstandingProposals被用来储存刚刚发起Proposal的消息,接下来如果收到法定人数(quorum)的ack,则将其删除并放入toBeApplied这个集合中,并最终传递到ZKDatabase中移出toBeApplied并放入代表已提交消息的committedLog集合中,画了一个图做一个小结,这里涉及到的所有集合后续都会讲解到:

  ![](https://ww1.sinaimg.cn/large/007iUjdily1g0ktz0xbrsj31oc0cmn16.jpg)



#### 6.1.3-2 SendAckRequestProcessor

当leader发送Proposal消息后,Follower开始参与进来,clientCnxn收到byteBuffer并解析后,进入FollowerZookeeper,调用![](https://ww1.sinaimg.cn/large/007iUjdily1g0l07vder9j31ca0e0wia.jpg)

可以看到还是先构造Request,因为Request类是Processor链路的通用对象. 然后进入SyncProcessor做持久化,Follower的SyncFollower的nextProcessor是SendAckRequestProcessor,所以append()完成后就进入SendAckRequestProcessor

```java
 public void processRequest(Request si) {
        if(si.type != OpCode.sync){
            QuorumPacket qp = new QuorumPacket(Leader.ACK, si.hdr.getZxid(), null,
                null);
            try {
                learner.writePacket(qp, false);
            } catch (IOException e) {
                try {
                    if (!learner.sock.isClosed()) {
                        learner.sock.close();
                    }
                } catch (IOException e1) {
                    LOG.debug("Ignoring error closing the connection", e1);
                }
            }
        }
    }
```

可以看到直接向leader发送ACK消息

#### 6.1.4 SyncRequestProcessor

我们知道只有事务消息才会进入到SyncRequestProcessor,这是因为只有事务消息才有被持久化的必要,这是前提

依旧是老套路,用LinkedBlockingQueue做异步,有俩,queuedRequests储存待处理的请求,toFlush存储待刷盘的请求,来看run()方法中的循环:
![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0kxfpphrcj31lo1scnaf.jpg)

- 1.snapCount这个参数所代表的意思是:每进行snapCount次事务日志输出后，触发一次快照(snapshot), 此时，ZK会生成一个snapshot.*文件，同时创建一个新的事务日志文件log.*。默认是100000.
  之所以要根据snapCount/2求随机数(设为r),是因为后面比较的时候,是比较的是snapCount/2+r,为什么不直接对SnapCount取随机数呢?因为这样随机范围太大,如果取的随机值比较小,那么会造成快照频率过高

- 2.从BQ中取值,如果此时toFlush为空,可以用take()来阻塞,poll()就是直接返回不会wait()

  看源码就知道了:
  ![](https://ww1.sinaimg.cn/large/007iUjdily1g0kxpqs4gyj30se0ne41e.jpg)

  ![](https://ww1.sinaimg.cn/large/007iUjdily1g0kxq9py6wj30v40mkad6.jpg)

  可以看到take()方法如果count为0,notEmpty这个Condition会将当前线程加入到条件队列并将其挂起

- 3.取出Request后,append到zkDatabase中,其实这个操作只会将这个Request中hdr和txn序列化而并不会真正的写入到文件,所以后面会添加到toFlush中

- 4.如果累计的logCount大于了根据snapCount算出的随机数,那么执行两个操作:1.快照 2.rollLog

  - 快照

    ![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0kxvn7ry3j31bu0baadn.jpg)

    根据lastZxid获取文件名并生成文件,然后将内存中的dataTree和session的map序列化到文件中

  - rollLog

    ![image-20190227135734903](/Users/wangwang/Library/Application Support/typora-user-images/image-20190227135734903.png)

    刷盘然后将logStream和oa置为空,然后在下一次调用append()的时候会重新初始化它们

    ![](https://ww1.sinaimg.cn/large/007iUjdily1g0kxyzgno0j31gi1b0qeg.jpg)

    

    

#### 6.1.5 CommitProcessor

```java
public class CommitProcessor extends ZooKeeperCriticalThread implements RequestProcessor {
    LinkedList<Request> queuedRequests = new LinkedList<Request>();
    LinkedList<Request> committedRequests = new LinkedList<Request>();

    RequestProcessor nextProcessor;
    ArrayList<Request> toProcess = new ArrayList<Request>();
    ......
}
```

维护了三个队列:queuedRequests,committedRequests,toProcess 分别存储等待commit的请求,已经commit的请求和待传入nextProcessor的请求
![](https://ww1.sinaimg.cn/large/007iUjdily1g0l3vus90fj310q0rs0wz.jpg)

在获得法定人数的ack之前和之后分别调用processRequest()和commit()方法分别加入到queuedRequests和committedRequests中

CommitProcessor可以划分为两种情况:
-1.Follower中的CommitProcessor,且client直接和Leader相连
-2.Leader中的CommitProcessor,或者是和client直连的Follower中的CommitProcessor
其实它俩的区别很简单,就是判断调用Zookeeper.submitRequest()没有,调用和没调用对应的CommitProcessor处理逻辑是不一样的,这是因为Zookeeper.submitRequest()会调用commitProcessor的processRequest(),将Request放入queuedRequests队列,在run()中会取出来然后尝试和committedRequests队列中的元素配对
而另一种情况则只会调用commit()方法把Request放入committedRequests队列,由于没有与之对应的放于queuedRequests中的Request,所以不需要配对,直接传入nextProcessor进行处理

![](https://ww1.sinaimg.cn/large/007iUjdily1g0l47gktw4j31h21yu4cf.jpg)



这段代码复杂度不算低的,私以为可以算作是zk源码里面最需要理清的代码块之一了,所以我们详细地来讨论和分析在CommitProcessor的run()中到底是有什么魔法?

其实分类讨论是解决这个问题的最好的办法,我们把所有Condition枚举出来,会发现这段代码很好地处理了所有的Condition

- 1.这是Leader的CommitProcessor,且此时处理的是一个与Leader直接相连的client的create请求(记为r1)的Proposal给peer但是还未收到法定人数的ack:

  这时queuedRequests.size()=1(因为ProposalProcessor会调用commitProcessor的processRequest()),所以可以一直往下走直到nextPending = request,这时nextPending被赋值为r1,然后回到loop的开头,这时还是没有获取足够的ack,所以会走到if(nextPending != null){continue;}然后又回到loop的开头
  

  ![](https://ww1.sinaimg.cn/large/007iUjdily1g0l4t3gvhoj31iw1z44er.jpg)

  leader在processAck()处理Proposal的广播,当收到超过半数的peer反馈的ack:
  1.从outstandingProposals中移出该zxid对应的Request,并加入到toBeApplied中

  2.调用commit()方法给所有Follower发送commit消息

  3.调用commitProcessor.commit()加入被提交的Request(记为r2)到committedRequests中
  这时会进入代码2将r1和r2进行对比,或者说"match"---**每一个加入到queuedRequests的Request都会强制性的match一个被加入到commitedRequests中的Request**,否则会不断地continue,直到匹配到对应的已被提交的Request才会转移到toProcess集合中.
  为什么一定要强制匹配?因为如果没有match上,那么代表此Proposal一直出于不能被commit的状态,zk要求事务消息是顺序的,所以只要有一个消息未被处理掉,那么后面的都会被阻塞—这种**强制性的顺序消息保证了zk的一致性**

  

- 2.这是Leader的CommitProcessor,且此时处理的是一个与Leader直接相连的client的getData请求(记为r1):

  代码会一直走到5,直接进入toProcess集合,因为这种非事务请求不需要quorum的ack,可以直接给到nextProcessor进行处理

- 3.这是Follower的CommitProcessor,且此时处理的是一个与Follower相连的client的Create请求(记为r1):

  这种情况其实是打算在后面单独介绍的,这里简单说一下流程:Follower会传递Request到FollowerRequestProcessor然后转发给Leader,之后进入CommitProcessor.那么这里其实和Condition1是一样的,都会在循环中进行自旋直到收到commit消息,然后进行match

- 4.这是Follower的CommitProcessor,且此时处理的是一个直接与Leader相连的client的Create请求(记为r1):

  


  Condition4和3的区别在于,请求的来源client是直接和Leader相连的,所以Follower此时不会调用commitProcessor的processRequest(),即queuedRequests的size一直都为0,所以当收到commit消息,会走到代码3中,直接进入toProcess集合不需要进行match


当事务请求被match,或者直接是非事务请求,会进入nextProcessor.在Leader中,这个nextProcessor是Leader.ToBeAppliedRequestProcessor, 在Follower中暑FinalRequestProcessor

#### 6.1.6 Leader.ToBeAppliedRequestProcessor

这个Processor是最简单的一个:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0l58xnwn3j31du0bg41f.jpg)

唯一的作用就是移出掉toBeApplied集合中的Request



#### 6.1.7 FinalRequestProcessor

```java
public void processRequest(Request request) {
    ......
    if (request.hdr != null) {
       TxnHeader hdr = request.hdr;
       Record txn = request.txn;

       rc = zks.processTxn(hdr, txn);
   }
   if (Request.isQuorum(request.type)) {
      zks.getZKDatabase().addCommittedProposal(request);
   }
    
   ......
   case OpCode.create: {
       lastOp = "CREA";
       rsp = new CreateResponse(rc.path);
       err = Code.get(rc.err);
       break;
   }
   ......
   long lastZxid = zks.getZKDatabase().getDataTreeLastProcessedZxid();
   ReplyHeader hdr = new ReplyHeader(request.cxid, lastZxid, err.intValue());

   cnxn.sendResponse(hdr, rsp, "response");
   ......
}
```

- 1.zookeeperServer处理事务消息,主要是调用getZKDatabase().processTxn(hdr, txn);
  ![](https://ww1.sinaimg.cn/large/007iUjdily1g0l5lgi2etj31be0qgdlz.jpg)

  从TxnHeader和TxnRecord获取zxid,path,data等数据并在dataTree中创建新节点

- 2.加入到zkDatabase的committedProposals集合中

- 3.创建CreateResponse和ReplyHeader并发送response给到client

### 6.2 Follower的processor处理链路

在6.1中其实已经涉及到了很多Follower的处理逻辑,主要是Leader开始Propose之后对应的Follower的操作,这里补上client直接向Follower发送事务请求后,Follower的处理链路



总结:

![](https://ww1.sinaimg.cn/large/007iUjdily1g0kyggwimsj31ra1iaqlq.jpg)




### 6.3 为什么没有rollback
最初看代码的时候,我很疑惑的是:类似于2PC的zab协议,为什么没有rollback机制?
换句话说,不管有没有达成quorum,zk都会将一个Request写入到(append)txnLogFile中,换句话说,在txnLogFile中存在各个节点不一致的数据

这个问题让我疑惑了好几天,反复地研究代码后,找到了问题的答案:其实因为zk满足了顺序事务机制,所以如果存在txnLogFile和DataTree数据不一致的问题就只有一种可能,在commit之前,集群不可用了,也就是leader一直无法得到超过半数的ack,由于peer之间的心跳机制,发生这种情况的时候,心跳会断开然后抛异常,zk的每个节点会恢复LOOKING状态,重新试图寻找leader

所以答案就只能在zk集群出现脑裂的时候去寻找,前面说过如果因为网络分区等原因不满足quorum条件,那么对于Leader和Follower来说,都会执行shutdown()方法.所以我们重点关注shutdown()的过程发生了什么:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0gnce11m0j31as166n4e.jpg)

- 1. 关闭所有网络通信(除了peer之间的通信管理器QuorumCnxManager),包括LearnerCnxAcceptor和client连接管理器cnxnFactory
- 2. 关闭LeaderZooKeeperServer
- 3. 关闭所有的LearnerHandler

第一和第三点都很容易理解,再看关闭LeaderZooKeeperServer发生了什么:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0gngtvzd9j319k118jy1.jpg)

- 1. 重置当前zkServer的状态为State.SHUTDOWN,这个状态会被ZooKeeperServerShutdownHandler监控(这个handler是在启动过程中被注册的,发生在ZookeeperServerMain.runFromConfig())
- 2. 关闭sessionTracker和firstProcessor
- 3. 重点来了,由于fullyShutDown传过来的是FALSE,所以这里并没有直接clear掉zkDb,而是调用它的fastForwardDataBase()

再来看fastForwardDataBase()中有什么秘密:
![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0gnl8mbiaj31pe05cdhi.jpg)

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0gnnrxckhj31g417yqdm.jpg)

- 1. txnLog.read(dt.lastProcessedZxid+1)代表从事务日志中读取比传入的值大的所有日志,而传入的是dataTree里面保持的最大的zxid,所以返回的数据是已经写入事务日志磁盘,但还未写入内存中的DataTree的Request,我们知道zk处理事务日志有一个责任链,写入事务日志发生在SyncRequestProcessor,而写入DataTree则发生在FinalProcessor,这两者中间leader需要等待quorumPeer的ACK回执,大于总数的一半之后才会继续往下走直到FinalProcessor,而Follower需要等待来自Leader的COMMIT信息,所以如果txnLog.read(dt.lastProcessedZxid+1)返回的迭代器有值,那么说明存在未被表决的消息在commit前,就shutdown了
- 2. 如果迭代器返回回空,就return
- 3. 否则重置highestZxid
- 4. 重置highestZxid后,处理这个已写入事务日志,但是未被commit的Proposal(即写入dataTree)

  5. 补充一点:4下面有一行listener.onTxnLoaded()会调用回调方法:

     ```java
         private final PlayBackListener commitProposalPlaybackListener = new PlayBackListener() {
             public void onTxnLoaded(TxnHeader hdr, Record txn){
                 addCommittedProposal(hdr, txn);
             }
         };
     ```

     在callback中调用addCommittedProposal(),这个方法除了在这里被调用,还会出现在FinalRequestProcessor中,作用是更新内存中的committedLog和maxCommittedLog

     所以在这里通过这个listener保持了dataTree和committedLog的一致

所以在shutdown()函数调用后
- 1. QuorumCnxManager,ZKDatabase,QuorumPeer得以留存,其余的都被关闭
- 2. **如果存在未被表决的消息,那么此时直接提交它**,这也是为什么没有rollback的原因,因为zk从不回滚,而是把所有消息最终都提交,从而保证了DataTree和txnLog的一致性

那么问题来了,把所有消息最终都提交会造成节点间数据的不一致吗?其实不会的,为什么?
前面分析过,造成这种情况只会发生在集群出现问题需要重新选主的时候,如果是正常情况,我们知道zk会顺序提交每一个Proposal.换句话说,如果顺序提交卡住了,那么一定是集群出问题了,需要重置各节点的角色.我们假设A节点为Leader,BC为Follower,A发出一个Proposal(P1)后因为网络分区的原因和BC失去心跳.由于断联了,这条Proposal肯定发送不出去也收不到来自Follower的ACK,所以在shutdown()之前dataTree没有,但txnLog有
![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0gody4k0yj30kg0fqgnf.jpg)

在shutdown()调用fastForwardFromEdits()后,这条未被commit的消息被强制commit了.
![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0gofxgcowj30j20iiwgh.jpg)

这时候网络恢复,在重新选主完成后,由于A的lastProcessedZxid最大,所以还是成为Leader.那么在同步数据的过程中,B和C得到的都是DIFF(**因为此时Leader的maxCommittedLog更大**)
A会给BC发送COMMIT消息来让B和C也写入刚刚的那条消息,从而实现了各节点的数据强一致性
![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0gohw3fvoj30hk08q3za.jpg)
![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0goixruw5j30ju0hgq4s.jpg)

所以小结一下:
为什么没有rollback?因为zk没有网络问题的时候,不需要rollback(因为是顺序提交),出现问题的时候,强制提交未通过决议的Proposal,在恢复集群后通过数据同步达到节点数据一致,所以其实这也可以说成是一种"最终一致性"



**补充**:区分FileTxnSnapLog.fastForwardFromEdits()和leader.startForwarding()

- 1.区别:
  其实从它们所属的类也能知道区别,前者的作用是在shutdown的时候,如果存在未被表决的消息,那么此时直接提交它,注意此时虽然未提交但是已经完成了syncProcessor阶段,即已经写入了txnLog文件中

  后后者发生在选主完成后的数据同步过程中,作用是:1.对于toBeApplied里面的消息,给Follower发送proposal和commit消息
  2.对于outstandingProposals里面的消息,给Follower发送Proposal消息

- 2.共同点:
  它们的目的都是为了数据的最终一致.前者在shutdown()后提交未表决的消息,这个操作其实也是在选举完成后的sync阶段起作用,因为DIFF状态的判断是根据maxCommittedLog和minCommittedLog来的,而fastForwardFromEdits()决定了或者说修改了maxCommittedLog和minCommittedLog,所以fastForwardFromEdits()**决定了主从是否是DIFF**,然后**同步已经持久化到文件中的差异数据**

  而startForwarding()发生在最后,即无论是DIFF,SNAP还是TRUNC,都会调用.目的是**同步内存中的差异数据**

  所以两者的结合,一个负责文件差异,一个负责内存差异,才能真正实现所谓的"最终一致性"

画了一个图总结一下:
  ![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0ksd0axy9j31a80qigrc.jpg)

  

  

  

 


## 7. zk的客户端服务端通信以及watcher和回调的实现
我们还是和上一节一样,以一个实际的请求来串联起整个处理逻辑,假设现在client发送一个读请求给Leader(因为我们要讲解回调的实现,所以这里是一个异步getDate)

### 7.1对于客户端Zookeeper类:

![](https://ww1.sinaimg.cn/large/007iUjdily1g0jlolg9q5j313q0u4tgc.jpg)

- 1.把传入的watcher包装成WatchRegistration对象,这个类将watcher和path绑定起来,并在"合适的时候"注册到ZKWatchManager中
  ![](https://ww1.sinaimg.cn/large/007iUjdily1g0jlzargvjj314y0yegst.jpg)
  如代码所示:
  shouldAddWatch(rc)实际上只比较了rc和0,如果等于0返回true,因为register()方法会在客户端收到response的时候被调用,也就是说所谓的"合适的时候"就是客户端请求成功收到回复的时候,注册到ZKWatchManager中后,当path对应的data有变化的时候,会通知(notification)客户端这一变化,客户端再根据path从ZKWatchManager中取出这里注册的watcher调用回调方法

- 2.在客户端中,zk使用三个类来标志一次请求:

  ![](https://ww1.sinaimg.cn/large/007iUjdily1g0jm66a35tj308i0cm74t.jpg)

  Requestheader:

  ```java
  public class RequestHeader implements Record {
    private int xid;
    private int type;
  }
  ```

  type代表请求类型,xid则是客户端赋予每个请求的标志id(可类比为服务端的zxid)

  所有的type定义如下:

  ```java
      public interface OpCode {
          public final int notification = 0;
  
          public final int create = 1;
  
          public final int delete = 2;
  
          public final int exists = 3;
  
          public final int getData = 4;
  
          public final int setData = 5;
  
          public final int getACL = 6;
  
          public final int setACL = 7;
  
          public final int getChildren = 8;
  
          public final int sync = 9;
  
          public final int ping = 11;
  
          public final int getChildren2 = 12;
  
          public final int check = 13;
  
          public final int multi = 14;
  
          public final int auth = 100;
  
          public final int setWatches = 101;
  
          public final int sasl = 102;
  
          public final int createSession = -10;
  
          public final int closeSession = -11;
  
          public final int error = -1;
      }
  ```

  

- 3.GetDataRequest这个类不像Requestheader,它在不同种类的请求中不同,例如getData对应GetDataRequest,而setData()则对应SetDataRequest类

  ```java
  public class GetDataRequest implements Record {
    private String path;
    private boolean watch;
  }
  ```

  path是路径,watch是一个布尔值,代表是否有设置watcher,这个布尔值会在服务端被使用,如果为TRUE,那么服务端会在维护的watcherManager中注册这个watcher

  (具体是在WatchManager中添加path和对应的ServerCnxn,其中ServerCnxn代表一个client和server的连接,它继承了Watcher,之所以继承Watcher,是因为server的dataTree发生变化,会回调注册的serverCnxn的process()方法,而在serverCnxn的process()方法中,会执行网络操作给对应的client发送notification通知event事件的发生,其实这段话就是zk实现watcher机制的核心了~)

  再给出SetDataRequest类的属性

  ```java
  public class SetDataRequest implements Record {
    private String path;
    private byte[] data;
    private int version;
  }
  ```

- 4.入ClientCnxn的发送队列(outgoingQueue)

###7.2对于ClientCnxn类

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0jo3dyt61j31ag07s403.jpg)

翻译过来就是:这个类为这个client管理着socket i/o,它维护着一个可连接Server的list并且可以"透明"地按需切换

![image-20190226113448576](/Users/wangwang/Library/Application Support/typora-user-images/image-20190226113448576.png)

- 1.构造Packet,需要注意的是Packet中包含了watchRegistration和Callback

  ```java
   static class Packet {
          RequestHeader requestHeader;
  
          ReplyHeader replyHeader;
  
          Record request;
  
          Record response;
  
          ByteBuffer bb;
  
          /** Client's view of the path (may differ due to chroot) **/
          String clientPath;
          /** Servers's view of the path (may differ due to chroot) **/
          String serverPath;
  
          boolean finished;
  
          AsyncCallback cb;
  
          Object ctx;
  
          WatchRegistration watchRegistration;
  
          public boolean readOnly;
   }
  ```

  Packet的属性如上图,它其实描述了着client和server通信的数据包,也就是说数据结构的转化链是这样:

  byteBuffer--->Packet--->CreateRequest

  画了一个图:

  ![](https://ww1.sinaimg.cn/large/007iUjdily1g0k0jh7c6bj32fw0l4114.jpg)

- 2.放入发送队列,看到队列我们其实一下就明白了,发送是走的异步,由一个线程去轮询获取队列中的元素,除了outgoingQueue,还有一个pendingQueue,从outgoingQueue中Poll出后,会加入到pendingQueue中,然后收到回复的bytebuffer,再从pendingQueue中remove出对应的Packet,然后:1.加入eventThread执行callback回调 2.注册watcher 这一部分的细节我们接下来一一呈现:

来看ClientCnxn的内部类:

![](https://ww1.sinaimg.cn/large/007iUjdily1g0jp9p3a84j31ym07uabz.jpg)

SendThread负责发送消息,也负责接收消息(所以说zk的有些类的命名不够严谨)

EventThread负责watcher和callback回调

### 7.3 SendThread

SendThread维护着一个ClientCnxnSocket,是和server的通信用的socket

在SendThread的run(0方法中,有一个while循环,首先是连接server

```java
clientCnxnSocket.connect(addr);
```

当建立了连接,即state.isConnected()==TRUE,每隔一段间隔时间发送ping给server

```java
        private void sendPing() {
            lastPingSentNs = System.nanoTime();
            RequestHeader h = new RequestHeader(-2, OpCode.ping);
            queuePacket(h, null, null, null, null, null, null, null, null);
        }
```

最后处理数据的IO:

```java
clientCnxnSocket.doTransport(to, pendingQueue, outgoingQueue, ClientCnxn.this);
```

这个方法是抽象方法,在子类ClientCnxnSocketNIO中实现(ClientCnxn没有netty版本)

```java
@Override
    void doTransport(int waitTimeOut, List<Packet> pendingQueue, LinkedList<Packet> outgoingQueue, ClientCnxn cnxn) throws IOException, InterruptedException {
	selector.select(waitTimeOut);
    Set<SelectionKey> selected;
    synchronized (this) {
        selected = selector.selectedKeys();
    }
    for (SelectionKey k : selected) {
        SocketChannel sc = ((SocketChannel) k.channel());
        .......
        else if ((k.readyOps() & (SelectionKey.OP_READ | SelectionKey.OP_WRITE)) != 0) {
                doIO(pendingQueue, outgoingQueue, cnxn);
            }
        }
        .......
    }
}
        
```

可以看到还是NIO的老套路,先是selector进行select,如果有可读或者可写的SelectionKey就进一步处理

#### 读

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0jsc6cr6yj31o218ik25.jpg)

分两部分讲解:

- 1.完整的读取流程:

  incomingBuffer和lenBuffer的定义:

  ```java
       /**
       * This buffer is only used to read the length of the incoming message.
       */
      protected final ByteBuffer lenBuffer = ByteBuffer.allocateDirect(4);
  
      /**
       * After the length is read, a new incomingBuffer is allocated in
       * readLength() to receive the full message.
       */
      protected ByteBuffer incomingBuffer = lenBuffer;
  ```

  lenBuffer只被用来读取消息的长度,给了4个字节,而且分配的是direct Memory(堆外内存)

  - ```
         /**
         * This buffer is only used to read the length of the incoming message.
         */
        protected final ByteBuffer lenBuffer = ByteBuffer.allocateDirect(4);
      
        /**
         * After the length is read, a new incomingBuffer is allocated in
         * readLength() to receive the full message.
         */
        protected ByteBuffer incomingBuffer = lenBuffer;
    ```

    lenBuffer只被用来读取消息的长度,给了4个字节,而且分配的是direct Memory(堆外内存)

    incomingBuffer最初被赋值为lenBuffer,是因为一开始需要先读length,然后再重新分配内存读取真正的消息

  在代码的一开始,先判断incomingBuffer.hasRemaining():

  ```java
      public final boolean hasRemaining() {
          return position < limit;
      }
  ```

  因为要确保length的四个字节或者消息体的消息都读完了,然后flip(),因为这时候position=limit,通过flip()把position置为0:

  ```java
      public final Buffer flip() {
          limit = position;
          position = 0;
          mark = -1;
          return this;
      }
  ```

  如果incomingBuffer等于lenBuffer,说明此时消息体已读完,需要重新读下一消息的长度,这时需要readLength():

  ```java
      protected void readLength() throws IOException {
          int len = incomingBuffer.getInt();
          if (len < 0 || len >= ClientCnxn.packetLen) {
              throw new IOException("Packet len" + len + " is out of range!");
          }
          incomingBuffer = ByteBuffer.allocate(len);
      }
  ```

  可以看到和上面说的一样,会重新分配内存给到incomingBuffer让它的长度等于消息体的长度,其实这时最常见的解决TCP黏包问题的方法---消息头部用四个字节写入长度,然后读取的时候先读头四节的拿到长度

  读完整个消息后,即hasRemaining()=FALSE,除了sendThread.readResponse(),还会clear掉lenBuffer,并重新赋值给incomingBuffer,代表下一次是去读消息长度了

  - 2.sendThread.readResponse()发生了什么?

    由于读取的数据和callback还有watcher相关性太强,所以后面单独详解

  #### 写

  ![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0jxajl55cj31gu186qcu.jpg)

  当sockKey可写的时候,首先从outgoingQueue中取出可发送的Packet,p.createBB()中将Packet的所有属性写入到一个byteBuffer中以便随后的发送,当sock.write()后,从outgoingQueue中移除,同时放入当前Packet到pendingQueue以供后续SendThread.readResponse()的处理

  最后熟悉NIO的同学都知道,如果当前无可发送的内容,这时候要反注册写事件,这是因为在NIO中,只要注册了写事件,在每次selector.select()中,写事件都会被触发,所以这里要反注册

  

  ### 7.4 SendThread.readResponse()-1

由于读数据和server相关性很强,所以后面的内容会穿插很多Server的逻辑

前面说了SendThread除了发送消息,也负责了读消息,readResponse()正是处理读取消息逻辑的函数

```java 
    void readResponse(ByteBuffer incomingBuffer) throws IOException {
        ByteBufferInputStream bbis = new ByteBufferInputStream(incomingBuffer);
        BinaryInputArchive bbia = BinaryInputArchive.getArchive(bbis);
        ReplyHeader replyHdr = new ReplyHeader();
        replyHdr.deserialize(bbia, "header");
        ......
    }
```
 先读出ReplyHeader,然后讨论ReplyHeader中的xid,其中当xid为-1的时候,代表此时有需要关注的事件产生![](https://pic.superbed.cn/item/5c7500db3a213b04179cea8e)

当xid为-1的时候,读取的byteBuffer里面的response是WatcherEvent对象,做一个转化转成WatchedEvent对象(它俩的区别是前者内部属性为int,被转化为了更具体的枚举值)

```java
   /**
     * Convert a WatcherEvent sent over the wire into a full-fledged WatcherEvent
     */
    public WatchedEvent(WatcherEvent eventMessage) {
        keeperState = KeeperState.fromInt(eventMessage.getState());
        eventType = EventType.fromInt(eventMessage.getType());
        path = eventMessage.getPath();
    }
```

这里也学到一个新单词:full-fledged-有充分资格的；发育完全的~

最后放入eventThread的事件队列等待处理

那么是谁,又是在哪里发送的这类xid为1的notification呢?答案当然是serverCnxn,而且是在serverCnxn的process(WatchedEvent)函数中发送的:

![](https://ww1.sinaimg.cn/large/007iUjdily1g0jy8z1psjj316k0fwjvc.jpg)

这个process()方法有什么特殊呢?因为它是Watcher类的抽象方法,其实ServerCnxn实现了watcher,它类似于一个中转站,zkServer中发生了任何Event,先触发serverCnxn,再由serverCxn的process()传递到对应的client

还是由最开始的例子(getData())来看看ServerCnxn到底是怎样做中转的:
我们知道zkServer在接收client请求后,经历若干processor的处理后,最终会经由FinalRequestProcessor落到持久层也就是ZKDatabase类来处理

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0jyegusn0j31ei0lq7ak.jpg)

![](https://ww1.sinaimg.cn/large/007iUjdily1g0jycmvdalj313k0hs42i.jpg)

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0jyft3hjaj317g0mo43v.jpg)

我们看到如果传入的Request中的watch==TRUE,那么最终会把path和ServerCnxn配对,然后保持在WatchManager中的watchTable和watch2Paths集合中(1.watch属性在之前已经做过伏笔,如果watcher不为空这里就是TRUE2.一个ServerCnxn代表一个client和server的连接,每个client都有一个单独的ServerCnxn对象与之对应)

当path对应的数据有变更的时候,会触发已经注册的watcher(即ServerCnxn),例如这时候有一个来自同一client的delete请求

DateTree中:

```java
public void deleteNode(String path, long zxid) {
    ......
    Set<Watcher> processed = dataWatches.triggerWatch(path,
                EventType.NodeDeleted);
    ......
}
```

![](https://ww1.sinaimg.cn/large/007iUjdily1g0jyqdj534j31es0xajyg.jpg)

最终,在这里调用ServerCxnx的process()

### 7.5EventThread

回到client,前面说到client收到notification后,放入eventThread的事件队列等待处理,这里的EventThread负责了watcher的回调和AsyncCallback的调用. 其实还是老套路,先放入waitingEvents中,然后run()方法中一个循环来处理remove出的对象
唯一不同的一点是这里入队写了两个方法,区分开了WatchedEvent和Packet.callback

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0jyxcg1o4j315u0s444j.jpg)

queueEvent()中首先要根据传入的watchedEvent拿到对应的pair,这个pair代表着事件和已注册watcher的配对,而注册过程前面提过,发生在包含这个watcher的请求返回成功的时候,值得一提的是,callback被调用也发生在同样的时候.换句话说,如果我们把收到response并处理包含在一次完整请求内,那么watcher被回调只能发生在第一次getData()请求后,而callback可以发生在第一次getData()请求中.

看代码:
![](https://ww1.sinaimg.cn/large/007iUjdily1g0jz43ohnmj31ba1aotkf.jpg)

和上面说的一样,如果是WatchedEvent,则回调注册的watcher,如果是Packet,则回调Packet中的callback

### 7.6 SendThread.readResponse()-2

SendThread.readResponse()还没读完呢,上文只分析到了xid=-1的时候,下面还有未读内容,也就是处理普遍的非特定的情况

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0jz8qh6bej31gq1b2qdw.jpg)

从pendingQueue取出第一个Packet,对比返回的response中的xid,如果不匹配则报错,这是因为zk认为client发消息和处理消息都是顺序的,发一个然后处理一个,是一一对应的,是严格按照顺序的

最后finishPacket(),这个方法其实很重要:

![](https://ww1.sinaimg.cn/large/007iUjdily1g0jzc07mj5j313k0ge41f.jpg)

- 1.前面提了很多次watcher的注册就发生在这里
- 2.我们一开始假定的是一个异步getData(),其实getData()还有同步版本,packet入队后就一直wait(),直到这里才被notify()
- 3.放入eventThread中的事件队列,触发传入Packet中的callback

到这里第七章就结束了,按照惯例,还是用流程图来总结watcher和callback的实现逻辑:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0jzg82apjj318e1os14f.jpg)

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0jzgqcu6vj30zg12c44r.jpg)



## 8. zk的数据存储

###8.1 Datatree

DataTree顾名思义,代表一棵树,我们知道zk的特点之一就是维护着一个类似文件系统的树状存储结构,或者说节点之间存在父子关系.

```java
public class DataTree {
    private final ConcurrentHashMap<String, DataNode> nodes =
        new ConcurrentHashMap<String, DataNode>();

    private final WatchManager dataWatches = new WatchManager();

    private final WatchManager childWatches = new WatchManager();
        
    private final PathTrie pTrie = new PathTrie();
}
```

1.在DataTree中使用一个ConcurrentHashMap存储着所有的数据节点
2.两个WatchManager分别管理着关注节点data变化的watcher和关注子节点变化的watcher
3.一颗Trie树维护着所有的quota节点

```java
public class DataNode implements Record {
    DataNode parent;
	byte data[];
    private Set<String> children = null;
    public StatPersisted stat;
}
```

1.而所谓树状结构是在DataNode中实现的,因为DataNode类中保存着它的父节点的引用,以及子节点的path集合

2.data字段存储节点对应的数据

3.stat则代表最终会被持久化的节点的状态信息,主要是时间和zxid

以在dataTree中创建新节点为例来看zk怎么存储所谓的树状结构的

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0lwoocye0j31i220se2d.jpg)

- 1.取出parentName和childName, 假设这个createData请求的完整路径是/zookeeper/quota/testA/zookeeper_limits
  那么取出的parentName是/zookeeper/quota/testA,childName是/zookeeper_limits
  构造StatPersisted对象,存入time和zxid,version设为0
- 2.创建新的DataNode对象,加入到全局node集合nodes中
     并加入childName到parentNode的children集合中
- 3.如果parentName以/zookeeper/quota开头,那么证明此请求是冲着quota来的,这将在下一节讲解
- 4.触发watcher,会取出watchManager中注册的watcher(即实现了watcher的serverCnxn),然后在serverCnxn的process()方法中发送notification给对应的client(这一块在上一章讲解过了)



###8.2 Trie树

上面讲到如果parentName以/zookeeper/quota开头,那么说明和quota有关

>ZooKeeper quota机制支持限制一个节点的子节点个数（znode）和data所占空间大小（字节数)
>当有超过限定的子节点limit后,会以打warning日志的方式做软性警告
>
>Client可以查看/zookeeper/quota目录下的数据来确定是否超出quota限制,例:
>[zk: localhost:2181(CONNECTED) 4] get /zookeeper/quota/testA/zookeeper_limits
>
>count=5,bytes=-1

在8.1的createNode代码的第三部分,如果parentName以/zookeeper/quota开头且childName等于zookeeper_limits,那么会在pTrie中添加path,这是因为此时这个请求实际就是quota请求,用来设定节点的limit限制,还是和上面的例子保持一致:此时client想创建一个path为/zookeeper/quota/testA/zookeeper_limits的节点,然后来看pTrie:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0m849y31bj319q20unbn.jpg)

TrieNode是PathTire的内部类,代表trie树上的节点

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0m8fn7pc7j314u0midk0.jpg)

理解addPath()还是以/zookeeper/quota/testA/zookeeper_limits为例,path.split()后得到testA和zookeeper_limits,testA之前已经创建过了,所以parent=testA,这时parent.getChild()为null,所以调用parent.addChild(),然后testA中的children这个map就有了一个entry: key = zookeeper_limits, value = new TrieNode()

画了一张图:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0m8gx5claj30ly0mitar.jpg)

其实这张图并不能和PathTrie定义的数据结构完全匹配,但是能更好地理解trie树的价值—它将前缀相同的节点置于同一条链路上面,在存储和检索中都有优势. 比如testA/testB和testA/testB/zookeeper_limits这两节点,存储在普通的集合显示花费的空间大于trie树,因为trie树对testA/testB这一两个节点共有的路径只存了一次. 而且在查询的时候,trie树也很方便地可以查出拥有共同前缀的词,这一特性经常被用于搜索框联想

###8.3 TxnLog





###8.4 SnapShot



