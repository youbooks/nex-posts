
# 1. Bootstrap

## 1.1 ServerBootstrap

### 1.1.1 一个例子:

来自官方源码example包下io.netty.example.echo.EchoServer类:

```java
EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        final EchoServerHandler serverHandler = new EchoServerHandler();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
             .channel(NioServerSocketChannel.class)
             .option(ChannelOption.SO_BACKLOG, 100)
             .handler(new LoggingHandler(LogLevel.INFO))
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 public void initChannel(SocketChannel ch) throws Exception {
                     ChannelPipeline p = ch.pipeline();
                     if (sslCtx != null) {
                         p.addLast(sslCtx.newHandler(ch.alloc()));
                     }
                     //p.addLast(new LoggingHandler(LogLevel.INFO));
                     p.addLast(serverHandler);
                 }
             });

            // Start the server.
            ChannelFuture f = b.bind(PORT).sync();

            // Wait until the server socket is closed.
            f.channel().closeFuture().sync();
        } finally {
            // Shut down all event loops to terminate all threads.
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
```

### 1.1.2 ServerBootstrap的属性

ServerBootstrap继承AbstractBootstrap

![](https://ws1.sinaimg.cn/large/006tNc79gy1g052y16jozj30oe0ucae4.jpg)

如图,代码中链式配置的各个属性基本都能和上面这张图对应上,这里需要说明的是:

在ServerBootstrap中,多了child相关的options,attrs,group和handler, 其实我们知道IO事件可分为三类: connect + read +write, 那么netty的设计是将connect和read+write 分开, 由boss处理连接事件, child处理连接后的读写事件

在bossGroup中维护了一个ServerChannel(或者说一个ServerChannel注册到了bossGroup中的一个eventLoop中),用它来负责客户端Channel的连接, 当客户端Channel连接后,netty会将它们注册到childGroup的eventLoop中

接下来我们先关注这个全局唯一的ServerChannel是如何产生的

### 1.1.3 ServerBootstrap启动过程

```java
// Start the server.
ChannelFuture f = b.bind(PORT).sync();
```

一切都是从这句代码开始的,接下来会走向父类AbstractBootstrap的doBind()方法

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0547lxnsaj31ki11we81.jpg)

这里有两个点需要注意:

- 1.initAndRegister()非常重要,因为它生成了Channel,并给Channel绑定了pipeline, handler 和 ChannelInitializer
- 2.这里用到了future和promise,详析请看附录

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g056bs0yy7j31sg0u6thg.jpg)

- 1 从工厂中获取一个新的channel
- 2 初始化这个channel,这个函数值得留意因为真实它区分开了ServerBootstrap和Bootstrap
- 3 从bossGroup中选取一个executor来执行channel的注册, 这里的**注册** 分为两层意思:

  - 1. 将当前channel和这个EventExecutor绑定,之后所有有关这个channel的runnable任务(如handler处理msg)都归这个EventExecutor管

  - 2. EventExecutor是EventLoop的父类,EventLoop除了处理runnable任务,还会负责IO,这里会将该channel和EventLoop里面的selector进行绑定,当selector发现该channel有IO事件发生的时候,会调用该channel的pipeline进行链式处理

来看ServerBootstrap的init(channel)函数:


![](https://ww1.sinaimg.cn/large/007i4MEmgy1g05rq29mgkj31tg1b8nbl.jpg)

1: 给channel设置option和attribute(都是用户指定的)

2: 注意,这里会显式地添加一个ChannelInitializer在pipeline的末尾, 对于ChannelInitializer顾名思义,作用是来负责channel的初始化的,netty中有完整的**事件体系**,ChannelInitializer的initChannel()方法会在channel的registered事件中被回调

3: 给pipeline添加用户设置的handler

4: 给pipeline添加ServerBootstrapAcceptor,顾名思义其作用是用来"接收"客户端的channel

注意:第3,4步都发生在channel被registered后








### 1.1.3 Bootstrap启动过程

ServerBootstrap的入口发生在bind(),而客户端Bootstrap的启动发生在connect(),其实他俩的启动过程基本都一样

区别点在上文已经阐述了,即init(channel)方法:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g05r47sa7nj31mw0kk441.jpg)







画了一张图来总结netty的启动过程

![](https://ws4.sinaimg.cn/large/006tNc79gy1g04uawq1m1j31zx0u0hdt.jpg)



# 2. Channel

## 2.1 Channel的生成

在上面介绍了,在initAndRegister()函数中, 通过 channel = channelFactory.newChannel(); 这句代码获得新的channel

其实netty使用反射的方式,根据其默认的构造方法获取对象

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g05w7w3uelj31jc0kyjvu.jpg)

在构造方法中:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g05w4qvd27j318w07cabj.jpg)

1: 获取unsafe

2:生成一个pipeline, 所以pipeline在channel生成的时候,就有了~

## 2.2 Unsafe

所有真正的IO操作都由Unsafe类来打理,官方给的注释是:

> <em>Unsafe</em> operations that should <em>never</em> be called from user-code. These methods
>
> are only provided to implement the actual transport

很容易联想到java中unsafe类, 我们经常用它来操作堆外内存(这一部分可以看我的另一篇文章)

Unsafe接口的所有方法如下:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g068aalmtkj31m00se101.jpg)

我们这里主要研究NIO的实现:

![image-20190214203951953](/Users/wangwang/Library/Application Support/typora-user-images/image-20190214203951953.png)

可以看到,NIO的实现主要体现在读(read)和写(flush),NIO使用selector来管理注册的channel,并调用select()方法来获取可读可写的selectionKey,然后调用selectionKey包含的(attachment)的channel,使用unsafe进行IO操作

这个IO操作可以简单试作两部分:

- 1.使用netty特有的ByteBuf,将channel中的msg写入到ByteBuf,那么这一步和NIO有什么关系呢,其实是因为这里的ByteBuf内部维护了一个ByteBuffer,而这个真正来实现数据操作的ByteBuffer是NIO包下的

- 2.当写入数据到ByteBuf中,会调用

  ```java
  pipeline.fireChannelReadComplete();
  ```

  然后读入的msg会在handler链中被inboundHandler们一一处理



Write:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g06al88ezvj31k60zqahw.jpg)

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g06angyti8j31bs0lagqm.jpg)

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g06avmkxmpj316k0esq5j.jpg)



![image-20190214215634699](/Users/wangwang/Library/Application Support/typora-user-images/image-20190214215634699.png)



![](https://ww1.sinaimg.cn/large/007i4MEmgy1g06auiqlikj313c0t0jxo.jpg)



![](https://ww1.sinaimg.cn/large/007i4MEmgy1g06b092i71j31ma0qajyy.jpg)









Read:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g06ahpom5rj31dq0x6qcb.jpg)

NioSocketChannel:

![image-20190214213920144](/Users/wangwang/Library/Application Support/typora-user-images/image-20190214213920144.png)







Flush:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g06afkiu13j31fy182tgw.jpg)

![image-20190214214028952](/Users/wangwang/Library/Application Support/typora-user-images/image-20190214214028952.png)

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g06a9vyc4lj31gw0lwjwg.jpg)

NioSocketChannel:

![image-20190214213826083](/Users/wangwang/Library/Application Support/typora-user-images/image-20190214213826083.png)











# 2. EventLoop

## 2.1 Executor

![](https://ws2.sinaimg.cn/large/006tNc79gy1g04uqu9o74j30u016tajm.jpg)

文字

![](https://ws3.sinaimg.cn/large/006tNc79gy1g04uoyk26xj31bq0u07fk.jpg)



最开始看netty源码的时候,很疑惑taskQueue里面到底在处理什么任务,后来仔细研究代码后发现,这里的"任务"真的是指除开IO之外的所有事,主要的构成是各个handler的read和write方法对msg的处理,看代码:

![](https://ws1.sinaimg.cn/large/006tNc79gy1g04vc13bt4j32cn0u0tol.jpg)

这段代码非常重要,会在如下两种情况被调用:

- 1.发生在msg处理链的起始

  当DefaultChannelPipeline调用fireChannelRead()时,会执行

```java
 AbstractChannelHandlerContext.invokeChannelRead(head, msg);
```

​	这里其实是selector发现可用的selectionKey后,调用其对应的Channel的pipeline的fireChannelRead(),然后传入head,开始链式地处理读入的msg

- 2.发生在msg处理链的中部

  某前面一个handler处理完一次msg,都会调用ctx.fireChannelRead(msg)来传递处理过的msg到next(下一个handlerContext)

在这个方法中,我们看到如果这个handlerContext所对应的Executor未和当前线程绑定,那么会构造一个runnable来异步执行,我们EventLoop的父类就是EventExecutor,所以EventLoop除了处理IO,还会花费一定比例(ratio)的时间来处理这种runnable任务

## 2.2 NioEventLoop

### 2.2.1 剖析类的构造

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g06bhd3y9oj31480wm0z7.jpg)

### 2.2.2 java的NIO用法回顾

#### 2.2.2.1 用法

学习这一部分要对NIO非常熟悉,所以对java中使用NIO做一个回顾和复习,这里摘抄了Zookeeper源码中使用NIO的部分

- 1. selector

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g06vzk0qf5j31qw16maol.jpg)

- 2.读

  ![image-20190215101512051](/Users/wangwang/Library/Application Support/typora-user-images/image-20190215101512051.png)

- 3.写

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g06whv42v4j31m81mch0t.jpg)

两个值得注意的地方:

- 1.这里为什么在数据都发送完成后会反注册写事件?

这是因为空闲状态下，所有的通道都是可写的. 也就是说如果不反注册,那么就会k.isWritable()会一直为true

- 2.ByteBuffer

NIO里面有2中Buffer 一种是 DirectBuffer 一种是 non-directbuffer.

DirectBuffer底层存储在非JVM堆上，通过native代码操作.

```java
    final ByteBuffer directBuffer = ByteBuffer.allocateDirect(64 * 1024);
```

non-directbuffer是heapbytebuffer,标准的java类

```java
ByteBuffer buffer = ByteBuffer.allocate(1024)
```

关于他俩的区别:

（1）	平时的read/write，都会在I/O设备与应用程序空间之间经历一个“内核缓冲区”。 
（2）	Direct Buffer就好比是“内核缓冲区”上的缓存，不直接受GC管理；而Heap Buffer就仅仅是byte[]字节数组的包装形式。因此把一个Direct Buffer写入一个Channel的速度要比把一个Heap Buffer写入一个Channel的速度要快。 
（3）	Direct Buffer创建和销毁的代价很高，所以要用在尽可能重用的地方。 



#### 2.2.2.2 NIO原理








# 3. Pipeline

![](https://ws2.sinaimg.cn/large/006tNc79gy1g04yxpjtmbj31es0a6q64.jpg)

所以为什么会传递给下一个handler处理呢?继续看代码:


![](https://ws1.sinaimg.cn/large/006tNc79gy1g04yru2g81j30w805u3zj.jpg)

![](https://ws3.sinaimg.cn/large/006tNc79gy1g04yth6jn5j310c07wwgb.jpg)


![](https://ws4.sinaimg.cn/large/006tNc79gy1g04ynfdp38j311w0u04qp.jpg)



# 4. ChannelHandlerContext

## 4.2 ChannelInboundHandler

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g06b518rs8j316e0ei0wq.jpg)

当时看到这个,我不禁和一位网友一样有个"惊天大疑问":

![image-20190214221406639](/Users/wangwang/Library/Application Support/typora-user-images/image-20190214221406639.png)

(链接:https://stackoverflow.com/questions/22354135/in-netty4-why-read-and-write-both-in-outboundhandler)

阅读了大神给的答案后,豁然开朗,于是写下来自己的理解:

![image-20190214221502055](/Users/wangwang/Library/Application Support/typora-user-images/image-20190214221502055.png)

(这是本人发布在一个论坛对一个帖子的回复)

我们从autoRead的配置入手,来看outboundHandler的read()方法是怎样工作的:







# 附录

## 1 future和promise

在java中其实一直没有真正**异步**,netty自己搞了一个promise来实现异步,其实呢原理很简单:

在future外面包一层promise,promise可以添加listener,listener必须实现一个operationComplete()方法,当逻辑上判定promise成功的时候,取出对应的promise(通常会把promise先存起来),然后调用它的trySuccess()或者setSuccess(),这时候会调用已经设置好的监听器里的回调方法, 上代码~

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g05643gdzoj31k60ae41s.jpg)


![](https://ww1.sinaimg.cn/large/007i4MEmgy1g05664ljelj313s09kwgb.jpg)

setSuccess0()--->setValue0():

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0567xgptmj31hc09kq5l.jpg)

我们看到,这里使用来CAS锁来保证并发的安全 

最后,通知监听器执行回调方法

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g054yxe8xnj31t40cadja.jpg)
















