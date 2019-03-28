## Netty其他相关

### netty的byteBuf

Netty 使用自建的 buffer API，而不是使用 NIO 的 ByteBuffer 来表示一个连续的字节序列。与 ByteBuffer 相比这种方式拥有明显的优势。 

netty中ByteBuf的缓冲区的优势： 

需要的话，可以自定义buffer类型；

通过组合buffer类型，可实现透明的零拷贝；

提供动态的buffer类型，如StringBuffer一样，容量是按需扩展；

无需调用flip()方法；

通常比ByteBuffer快。

Netty的zero-copy则是完全在用户态，Netty通过ByteBuf.slice以及Unpooled.wrappedBuffer等方法拆分、合并Buffer无需拷贝数据。

#### 堆内存和直接内存

1) 堆内存字节缓冲区（HeapByteBuf）

内存的分配和回收速度快，可以被JVM自动回收，缺点是如果进行socket的I/O读写，需要额外做一次内存复制，将堆内存对应的字节缓冲区复制到内核Channel中，性能会有一定的下降。

2) 直接内存字节缓冲区（DirectByteBuf）

非堆内存，它在堆外进行内存分配，相比于堆内存，它的分配和回收速度会慢一些，但是将他写入或者从SocketChannel中读取出是，由于少了一次内存复制，速度比堆内存快。

netty写数据用的ChannelOutboundBuffer就是直接内存缓冲区，这样在写入socket缓冲区的时候少了一次内存复制，速度更快；在读数据时，netty使用的PooledUnsafeDirectByteBuf也是直接内存缓冲区，同样减少内存复制，速度更快。

### 粘包、拆包

粘包和拆包是TCP协议中两个很常见的问题。

在用户数据量非常小的情况下，如果TCP数据包每次只有一个字节，该TCP数据包的有效载荷非常低，传递x字节的数据，需要x次TCP传送，x次ACK，在应用及时性要求不高的情况下，TCP设计了Nagle算法，将多次间隔较小、数据量小的数据，合并成一个大的数据块，然后进行封包。这样只会传输一次，返回一个ACK。
拆包和粘包是相对的，一端粘了包，另外一端就需要将粘过的包拆开。另外，用户数据包超过了mss(最大报文长度)，那么这个数据包在发送的时候必须拆分成几个数据包，接收端收到之后需要将这些数据包粘合起来之后，再拆开。

需要拆包的根本原因是：TCP是基于字节流的传输协议，每次报文传输的长度有效，而应用层需要区分不同的数据单元（例如每次完整的请求都是一个数据单元，每个请求的数据可能会分成几个数据报文传输，也可能与其他请求单元合并在一个数据报文传输。而server的应用层必须区分开这些请求数据单元）

基于这个原因，可以设想出拆包的基本原理：

在应用层，如果当前读取的数据不足以拼接成一个完整的业务数据单元，那就保留该数据，继续从tcp缓冲区中读取，直到得到一个完整的数据单元；

如果当前读到的数据加上已经读取的数据足够拼接成一个数据单元，那就将已经读取的数据拼接上本次读取的数据，形成一个完整的数据单元，然后将这个数据单元传递到业务逻辑（即其后的channelHandler）；

如果组成完整的数据单元后还有多余的数据，则将数据仍然保留，下次读到的数据尝试拼接。

netty 中的拆包就是这个原理，内部会有一个累加器，每次读取到数据都会不断累加，然后尝试对累加到的数据进行拆包，拆成一个完整的业务数据单元，netty拆包的基类是ByteToMessageDecoder。

```java
public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof ByteBuf) {
      CodecOutputList out = CodecOutputList.newInstance();
      try {
        ByteBuf data = (ByteBuf) msg;
        first = cumulation == null;
        if (first) {
          cumulation = data;
        } else {
          cumulation = cumulator.cumulate(ctx.alloc(), cumulation, data);
        }
        callDecode(ctx, cumulation, out);
      } catch (DecoderException e) {
        throw e;
      } catch (Throwable t) {
        throw new DecoderException(t);
      } finally {
        if (cumulation != null && !cumulation.isReadable()) {
          numReads = 0;
          cumulation.release();
          cumulation = null;
        } else if (++ numReads >= discardAfterReads) {
          
          numReads = 0;
          discardSomeReadBytes();
        }
 
        int size = out.size();
        decodeWasNull = !out.insertSinceRecycled();
        fireChannelRead(ctx, out, size);
        out.recycle();
      }
    } else {
      ctx.fireChannelRead(msg);
    }
}
```

拆包过程分为四步：

1. 累加数据
2. 将累加到的数据传递给业务进行业务拆包
3. 清理字节容器
4. 传递业务数据包给业务解码器处理

用户需要自定义业务拆包可以选择继承ByteToMessageDecoder，实现decode，就可以按照自己的业务模型进行拆包了。