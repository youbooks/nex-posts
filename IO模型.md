# 1. 4种IO模型

- 同步阻塞 IO
- 同步非阻塞 IO
- IO 多路复用
- 异步非阻塞IO

## 1.1同步阻塞 IO

![image-20190218181011355](/Users/wangwang/Library/Application Support/typora-user-images/image-20190218181011355.png)



## 1.2同步非阻塞 IO

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0ar0t2jz5j30t415wdma.jpg)

## 1.3 IO 多路复用

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0arcnroivj30ts0o4wi0.jpg)

### Epoll的实现原理

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0arj0nk3lj30h30emwfj.jpg)



## 1.4异步非阻塞IO



![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0argagvrlj30sk0og77g.jpg)



AIOServer:

```java
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
public class Server {
	private static Charset charset = Charset.forName("US-ASCII");
    private static CharsetEncoder encoder = charset.newEncoder();
	
	public static void main(String[] args) throws Exception {
		AsynchronousChannelGroup group = AsynchronousChannelGroup.withThreadPool(Executors.newFixedThreadPool(4));
		AsynchronousServerSocketChannel server = AsynchronousServerSocketChannel.open(group).bind(new InetSocketAddress("0.0.0.0", 8013));
		server.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
			@Override
			public void completed(AsynchronousSocketChannel result, Void attachment) {
				server.accept(null, this); // 接受下一个连接
				try {
					 String now = new Date().toString();
					 ByteBuffer buffer = encoder.encode(CharBuffer.wrap(now + "\r\n"));
					//result.write(buffer, null, new CompletionHandler<Integer,Void>(){...}); //callback or
					Future<Integer> f = result.write(buffer);
					f.get();
					System.out.println("sent to client: " + now);
					result.close();
				} catch (IOException | InterruptedException | ExecutionException e) {
					e.printStackTrace();
				}
			}
			@Override
			public void failed(Throwable exc, Void attachment) {
				exc.printStackTrace();
			}
		});
		group.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
	}
}
```

AIOClient:

```java
public class Client {
	public static void main(String[] args) throws Exception {
		AsynchronousSocketChannel client = AsynchronousSocketChannel.open();
		Future<Void> future = client.connect(new InetSocketAddress("127.0.0.1", 8013));
		future.get();
		
		ByteBuffer buffer = ByteBuffer.allocate(100);
		client.read(buffer, null, new CompletionHandler<Integer, Void>() {
			@Override
			public void completed(Integer result, Void attachment) {
				System.out.println("client received: " + new String(buffer.array()));
				
			}
			@Override
			public void failed(Throwable exc, Void attachment) {
				exc.printStackTrace();
				try {
					client.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
								
			}
		});
		
		Thread.sleep(10000);
	}
}
```







# Reactor和Proactor

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0aqrs7s2xj30rs0hp77p.jpg)

单线程Reactor模式:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0aqs2o70cj30rs0axwgo.jpg)

多线程Reactor模式:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0aqslr882j30rs0ma785.jpg)



## 关于netty的零拷贝

![image-20190218181813421](/Users/wangwang/Library/Application Support/typora-user-images/image-20190218181813421.png)



![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0aqpctzk2j31ag0k6jxb.jpg)

