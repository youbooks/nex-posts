## Netty的PipeLine

EventLoop是一个无限循环，netty中几乎所有事件和任务都是在EventLoop中执行的。而这些事件或任务大多会通过netty的ChannelPipeLine在ChannelHandler链执行下去。

在创建Netty的Channel的构造函数中，都会创建一个ChannelPipeLine。

```java
protected AbstractChannel(Channel parent) {
    this.parent = parent;
    id = newId();
    unsafe = newUnsafe();
   pipeline = newChannelPipeline();
}
```

AbstractChannel 有一个 pipeline 字段, 在构造器中会初始化它为 DefaultChannelPipeline的实例。而AbstractChannel是NioSocketChannel和NioServerSocketChannel的父类，因此，netty每一个channel都有一个ChannelPipeLine对象。也就是每条连接都会有一个ChannelPipeLine对象，ChannelPipeLine的通道处理每条连接IO事件的操作。

### PipeLine模型
在channel中创建的ChannelPipeLine都是DefaultChannelPipeline，会把channel自身当做参数传给DefaultChannelPipeline，让DefaultChannelPipeline同样持有channel对象。

```java
protected DefaultChannelPipeline(Channel channel) {
    this.channel = ObjectUtil.checkNotNull(channel, "channel");
    succeededFuture = new SucceededChannelFuture(channel, null);
    voidPromise =  new VoidChannelPromise(channel, true);

    tail = new TailContext(this);
    head = new HeadContext(this);
     
    head.next = tail;
    tail.prev = head;
}
```

从DefaultChannelPipeline的构造方法中，可以看到有两个ChannelHandlerContext对象，head和tail，head和tail是ChannelHandler链的头尾两个节点，head是HeadContext的实例，tail是TailContext的实例。head是tail有一些不同，head同时是ChannelInboundHandler和ChannelOutboundHandler，而tail只是一个ChannelInboundHandler，这意味着channel的读写都会通过head，而只有读会通过tail。同时，head中持有channel的unsafe对象，表面head是与IO操作最密切相关的。

ChannelHandlerContext节点中包裹了执行器ChannelHandler以及ChannelPipeLine自身。ChannelHandlerContext节点是一个双向链表，含有prev和next节点，这就组成了一个ChannelHandler链。ChannelPipeLine的模型如下：

![](https://ws3.sinaimg.cn/large/006tNbRwgy1fxauidw0q9j30fj083jrd.jpg)

最原始的ChannelPipeLine的ChannelHandler链只有head和tail两个节点，通过ChannelPipeLine的addLast和addFirst方法添加channelHandler。

```java
public final ChannelPipeline addLast(EventExecutorGroup group, String name, ChannelHandler handler) {
    final AbstractChannelHandlerContext newCtx;
    synchronized (this) {
      // 检查是否有重复的handler
      checkMultiplicity(handler);
			// 创建ChannelHandlerContext节点
      newCtx = newContext(group, filterName(name, handler), handler);
			// 添加节点到链表中
      addLast0(newCtx);
 
      if (!registered) {
        newCtx.setAddPending();
        callHandlerCallbackLater(newCtx, true);
        return this;
      }
 
      EventExecutor executor = newCtx.executor();
      if (!executor.inEventLoop()) {
        newCtx.setAddPending();
        executor.execute(new Runnable() {
          @Override
          public void run() {
            // 回调用户方法
            callHandlerAdded0(newCtx);
          }
        });
        return this;
      }
    }
    callHandlerAdded0(newCtx);
    return this;
}

```

添加channelHandler链节点的操作也分为四步：

检查pipeline中是否有重复的channelHandler

基于此channelHandler创建一个ChannelHandlerContext节点

将节点添加到链表中

添加成功后回调用户handlerAdded方法

前面两步都比较简单，第三步添加节点到链表中就是一个很常规的双向链表的插入操作，添加过程如图：

![](https://ws4.sinaimg.cn/large/006tNbRwgy1fxaujsb81dj30fg07ndfr.jpg)

插入成功后的结果：

![](https://ws3.sinaimg.cn/large/006tNbRwgy1fxaujwxujyj30l50343yd.jpg)

删除与插入差不多，都是对双向链表的操作。

最后一步是回调用户的handlerAdded方法，即回调此handler的handlerAdded，这样channelHandler可以及时感知何时被添加到channelPipeLine中，是一个设计得很好的地方。

PipeLine的责任链
--------------------- 

讲完ChannelPipeLine的结构和模型，接下来具体来看看PipeLine的这条ChannelHandler链。

下图是netty的官方文档ChannelPipeLine的图

![](https://ws2.sinaimg.cn/large/006tNbRwgy1fxaukihd2zj30u00x1dh0.jpg)

可以看出netty将ChannelHandler分为两种类型，InboundHandler和OutboundHandler，分别对应着两种类型的节点。InboundHandler处理inBound事件，最典型的就是读取数据流，OutboundHandler处理outBound事件，处理写数据流。InboundHandler是从head流向tail，OutboundHandler是从tail流向head。上面已经知道head节点既是InboundHandler也是OutboundHandler，所以读写都会通过head节点。而read正好是最开始读数据出来再做处理，write正好是最后处理完才写出去，所以不管是read还是write都是最后与IO交互的都是head节点，因此head节点中持有unsafe对象，将所有IO的操作都委托给Unsafe执行。

![](https://ws2.sinaimg.cn/large/006tNbRwgy1fxaukshc63j30vg0s2dg9.jpg)



这是nio相关的Unsafe类继承关系，Unsafe 都是属于Channel的内部类，NioMessageUnsafe是NioServerSocketChannel相关的Unsafe类，NioByteUnsafe是NioSocketChannel相关的Unsafe类，分别对应着处理新连接accept相关的操作和每条连接读写相关的操作。

再来看看很常见的一条Pipeline的ChannelHandler链，分为InboundHandler和OutboundHandler，如图

![](https://ws4.sinaimg.cn/large/006tNbRwgy1fxaul3stl9j30xb04m0sp.jpg)

read事件的时候，ChannelHandler链只会依次通过head、FrameDecoder、StringDecoder、ChatClientHandler、tail这些节点，而write时，ChannelHandler链只会依次通过StringEncoder、head这两个节点。
