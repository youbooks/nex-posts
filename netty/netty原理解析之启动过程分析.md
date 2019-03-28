## server是如何启动的

在看netty启动之前，先来看看jdk原生的nio server是如何启动的

```java
// 创建一个ServerSocketChannel对象
ServerSocketChannel ssc = ServerSocketChannel.open();
// 绑定tcp端口,监听请求,也可以放在后面
ssc.socket().bind(new InetSocketAddress("127.0.0.1", 8000));
// 设置io模式为非阻塞
ssc.configureBlocking(false);

// 创建一个Selector
Selector selector = Selector.open();
// 注册 channel，并且指定感兴趣的事件是 Accept
ssc.register(selector, SelectionKey.OP_ACCEPT);

```


分为五步

- 创建一个ServerSocketChannel对象

- 绑定tcp端口,监听请求,也可以放在后面

- 设置io模式为非阻塞

- 创建一个Selector

- 注册 channel，并且指定感兴趣的事件是 Accept

再来看看netty服务端启动的代码

```java

public class ChatNettyServer {

    private int port;
     
    public ChatNettyServer(int port) {
        this.port = port;
    }
     
    public void start() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
     
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChatServerInitializer());
     
            System.out.println("ChatNettyServer 启动了");
            // 绑定端口，开始接收进来的连接
            ChannelFuture f = b.bind(port).sync();
     
            f.channel().closeFuture().sync();
        } catch (Exception e) {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
            System.out.println("ChatNettyServer 关闭了");
     
        }finally {
            System.out.println("finally");
        }
    }
}
```

大致意思是netty开启一个tcp server，将端口绑定在port上，使用nio的模式。

这里有一些组件已经是上面我们提到的了，比如ServerBootstrap，server启动器设置一系列参数，比如EventLoopGroup、ChannelHandler、Channel等，最后绑定端口启动服务；EventLoopGroup的主要作用是生成事件循环处理器EventLoop；NioServerSocketChannel是通过反射的方式生成服务端的channel，负责监听一个tcp端口。

最后通过ServerBootstrap的bind(port)方法真正绑定端口，完成服务端启动的工作。

netty的服务端启动主要分为三步：

- init

- register

- bind

因此带来的三个问题分别是：

init初始化什么？

register向谁注册了什么？

跟什么绑定了？

### init初始化

ServerBootstrap从bind方法开始调用，先调用validate验证ServerBootstrap上的参数，然后调用doBind方法进入到initAndRegister方法中，在initAndRegister方法中完成init和register的工作。

init和register的源码：

```java
final ChannelFuture initAndRegister() {
    Channel channel = null;
    // 1、init过程
    try {
        channel = channelFactory.newChannel();
        init(channel);
    } catch (Throwable t) {
        if (channel != null) {
            channel.unsafe().closeForcibly();
        }
        return new DefaultChannelPromise(channel, GlobalEventExecutor.INSTANCE).setFailure(t);
    }
    // 2、register的过程
    ChannelFuture regFuture = config().group().register(channel);
    if (regFuture.cause() != null) {
        if (channel.isRegistered()) {
            channel.close();
        } else {
            channel.unsafe().closeForcibly();
        }
    }

    return regFuture;

}
```

init初始化完成两件事

- 创建一个channel对象

- 初始化这个channel

创建channel对象是由channelFactory这个工厂类生成的，根据NioServerSocketChannel.class反射生成ServerChannel对象。

实例化NioServerSocketChannel也分为几个阶段，NioServerSocketChannel对象的创建是从NioServerSocketChannel一层一层调用父类的构造方法创建的，一直到AbstractChannel的构造方法创建完成。

通过jdk的nio原生方法生成ServerSocketChannel对象（最后监听端口就是通过这个对象监听的），绑定到NioServerSocketChannel上，设置ServerSocketChannel为非阻塞方式

调用newUnsafe()方法生成一个Unsafe对象，这里的Unsafe对象是在AbstractNioMessageChannel类中生成的，所以是一个NioMessageUnsafe的实例，不同的Unsafe子类完成的工作不同，NioMessageUnsafe负责处理accept事件。

调用newChannelPipeline()方法生成一个DefaultChannelPipeline对象，DefaultChannelPipeline中默认有head和tail两个节点的ChannelHandler，后续再加ChannelHandler都是在这条链上。

经过这几步操作，完成了NioServerSocketChannel的实例化过程，接下来就是channel的init了。

init channel也分了几步

获取设置在bootstrap上的options和attrs，将其注入到channel中

获取设置在bootstrap上的ChannelHandler，将其加入到channel的ChannelPipeline的处理链上，这样server channel上的任何事件都会经过这个ChannelHandler处理

最后生成一个ServerBootstrapAcceptor对象，这同样是一个ChannelHandler，将其也加入到channel的ChannelPipeline的处理链上ServerBootstrapAcceptor的作用是：监听到accept事件，生成NioSocketChannel对象，然后将这个对象注册到worker reactor上，让worker reactor负责这条连接的读写。

客户端的bootstrap并没有最后一步生成ServerBootstrapAcceptor的过程，因为客户端不需要监听端口，只需要处理一条连接的读写，这是netty服务端和客户端启动初始化的区别。

netty启动init阶段初始化了一些基本的配置和属性，以及在pipeline上加入了一个接入器，用来专门接受新连接。

而在nio层面，完成了创建一个ServerSocketChannel对象和设置io模式为非阻塞这两步操作。

### register注册

完成了启动初始化工作之后，接下来netty要开始register注册了，register主要是注册channel。

在流程上，从initAndRegister方法出发，调用路径如下：

```
AbstractBootstrap.initAndRegister() —> EventLoopGroup.register(channel) —> EventLoop.register(channel) —> AbstractUnsafe.register(this, promise) —> AbstractNioUnsafe.doRegister()
```

到了AbstractNioUnsafe的doRegister方法，netty基本上只做了一件事，jdk原生的serverChannel向selector注册感兴趣的事件

selectionKey = javaChannel().register(eventLoop().selector, 0, this);
只是这里注册的opts是0，表示此channel不关注任何类型的事件。（言外之意，register方法只是获取一个selectionKey，具体这个Channel对何种事件感兴趣，可以在稍后操作）

所以真正注册对accept是在后面。

### bind绑定

完成了启动的初始化和假注册之后，netty需要真正绑定端口监听了。bind过程主要就是完成真正注册关注accept事件，bind端口完成启动工作。

bind的流程调用有两条路径，通过eventLoop异步执行task，流程如下：

```
AbstractChannel.bind(localAddress, promise) —> DefaultChannelPipeline.bind(localAddress, promise) —> tail.bind(localAddress, promise) —> head.bind(this, localAddress, promise) —> AbstractUnsafe.bind(localAddress, promise) —> NioServerSocketChannel.doBind(localAddress)
```

```
AbstractChannel.bind(localAddress, promise) —> DefaultChannelPipeline.fireChannelActive() —> head.invokeChannelActive() —> head.readIfIsAutoRead() —> AbstractChannel.read() —> DefaultChannelPipeline.read() —> tail.read() —> head.read() —> AbstractUnsafe.beginRead() —> AbstractNioUnsafe.doBeginRead()
```

第一条路径是执行绑定操作，监听你设置的端口号。最后执行到NioServerSocketChannel的doBind方法，调用jdk的ServerSocketChannel执行bind操作：javaChannel().bind(localAddress, config.getBacklog());

而第二条路径是完成真正关注accept事件，先获取在register阶段获得的selectionKey，然后真正注册对accept事件感兴趣，这样channel就可以接收所有连接请求了，boss reactor将这些请求都通过ServerBootstrapAcceptor连接器处理，把这些请求注册给worker reactor，让worker reactor负责这条连接的读写。

```java
protected void doBeginRead() throws Exception {
    // Channel.read() or ChannelHandlerContext.read() was called
    final SelectionKey selectionKey = this.selectionKey;
    if (!selectionKey.isValid()) {
        return;
    }

    readPending = true;
     
    final int interestOps = selectionKey.interestOps();
    if ((interestOps & readInterestOp) == 0) {
        // 真正注册对accept事件感兴趣
        selectionKey.interestOps(interestOps | readInterestOp);
    }
}
```

最后，netty客户端的启动过程跟服务端启动流程相似，客户端的是init、register和connect，而且客户端的启动比服务端简单，感兴趣可以自行关注。
