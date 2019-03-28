## Netty如何处理事件

讲完EventLoop和ChannelPipeLine，接下来看看netty具体是如何处理事件，把EventLoop和ChannelPipeLine关联上的。

netty处理accept、read、write事件的过程各不相同

boss reactor的selector轮询到accept事件，然后调用NioMessageUnsafe的read方法，通过ServerSocketChannel的accept方法获取到一个SocketChannel，然后NioMessageUnsafe调用ChannelPipeLine串行执行channelHandler的channelRead方法，在ServerBootstrapAcceptor将NioSocketChannel注册到worker reactor中的selector上去，让worker reactor负责这个channel的读写。

worker reactor的selector轮询到read事件，然后调用NioByteUnsafe的read方法，调用pipeline.fireChannelRead(byteBuf)方法，通过worker reactor的ChannelPipeLine串行执行channelHandler的channelRead方法。

write操作跟accept事件和read事件不太一样，write操作一般是用户通过channel.write调用的。channel.write实际上也是调用pipeline.write(msg)方法，一般情况下netty将write操作封装成一个WriteTask任务，提交给EventLoop，EventLoop在runAllTasks方法中执行这个task。在WriteTask的run方法中，netty执行ChannelPipeLine的outBound channelHandler链，依次执行handler的write方法，最后通过head的write方法委托给unsafe.write(msg, promise)方法把数据写给socket缓冲区。

### netty处理连接事件

![](https://ws3.sinaimg.cn/large/006tNbRwgy1fxaumyboldj313x0flweu.jpg)


第一个NioEventLoop是boss reactor，第二个NioEventLoop是worker reactor。boss reactor的selector轮询到accept事件，调用processSelectedKeys处理accept事件，然后调用NioMessageUnsafe的read方法，通过ServerSocketChannel的accept方法获取到一个SocketChannel，然后NioMessageUnsafe调用ChannelPipeLine串行执行channelHandler的channelRead方法，在ServerBootstrapAcceptor中调用NioEventLoop的register方法，最后调用AbstractNioChannel的doRegister方法，将NioSocketChannel注册到worker reactor中的selector上去，让worker reactor负责这个channel的读写。

### netty处理read事件

![](https://ws3.sinaimg.cn/large/006tNbRwgy1fxaun3pvk9j30zp0hjt94.jpg)


worker reactor的selector轮询到read事件，然后调用NioByteUnsafe的read方法，调用pipeline.fireChannelRead(byteBuf)方法，通过worker reactor的ChannelPipeLine串行执行channelHandler的channelRead方法。

### netty处理write操作

![](https://ws4.sinaimg.cn/large/006tNbRwgy1fxaun7xa83j318e0g7wf0.jpg)

write操作跟accept事件和read事件不太一样，write操作一般是用户主动通过channel.write调用的，这就意味着write操作可能在EventLoop的循环IO线程中（直接在channelHandler执行write操作），也可能在用户自定义线程中执行。当然大部分请求下都是在用户线程执行，因为我们的IO线程的所有操作都是串行执行的，如果放在IO线程，会阻塞IO线程，影响netty的性能。

channel.write实际上也是调用pipeline.write(msg)方法，然后netty将write操作封装成一个WriteTask任务，提交给EventLoop，EventLoop在runAllTasks方法中执行这个task。在WriteTask的run方法中，netty执行ChannelPipeLine的outBound的channelHandler链，依次执行handler的write方法，最后造head的write方法中，委托给unsafe.write(msg, promise)方法把数据写到netty自定义的ChannelOutboundBuffer缓冲区中（DirectByteBuf，在堆之外直接分配内存，直接缓冲区不会占用堆的容量，可以实现零拷贝），最后调用flush方法，才真正执行doWrite操作，调用NioSocketChannel通过jdk的channel写出数据流。

## netty处理write的代码解析

io.netty.channel.AbstractChannelHandlerContext

```java
    private void write(Object msg, boolean flush, ChannelPromise promise) {
        //@1
        AbstractChannelHandlerContext next = findContextOutbound();
        final Object m = pipeline.touch(msg, next);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            if (flush) {
                next.invokeWriteAndFlush(m, promise);
            } else {
                //@2
                next.invokeWrite(m, promise);
            }
        } else {
            final AbstractWriteTask task;
            if (flush) {
                task = WriteAndFlushTask.newInstance(next, m, promise);
            }  else {
                task = WriteTask.newInstance(next, m, promise);
            }
            if (!safeExecute(executor, task, promise, m)) {
                // We failed to submit the AbstractWriteTask. We need to cancel it so we decrement the pending bytes
                // and put it back in the Recycler for re-use later.
                //
                // See https://github.com/netty/netty/issues/8343.
                task.cancel();
            }
        }
    }

......
    
    
        private void invokeWrite(Object msg, ChannelPromise promise) {
        if (invokeHandler()) {
            invokeWrite0(msg, promise);
        } else {
            write(msg, promise);
        }
    }
```

```java
    private void invokeWrite0(Object msg, ChannelPromise promise) {
        try {
            ((ChannelOutboundHandler) handler()).write(this, msg, promise);
        } catch (Throwable t) {
            notifyOutboundHandlerException(t, promise);
        }
    }

```

@1:寻找pipeline里面"下一个"handler

@2:执行当前handler的write操作

以io.netty.handler.codec.MessageToByteEncoder为例:

```java
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        ByteBuf buf = null;
        try {
            if (acceptOutboundMessage(msg)) {
                @SuppressWarnings("unchecked")
                I cast = (I) msg;
                buf = allocateBuffer(ctx, cast, preferDirect);
                try {
                    encode(ctx, cast, buf);
                } finally {
                    ReferenceCountUtil.release(cast);
                }

                if (buf.isReadable()) {
                    //@1
                    ctx.write(buf, promise);
                } else {
                    buf.release();
                    //@2
                    ctx.write(Unpooled.EMPTY_BUFFER, promise);
                }
                buf = null;
            } else {
                ctx.write(msg, promise);
            }
        } catch (EncoderException e) {
            throw e;
        } catch (Throwable e) {
            throw new EncoderException(e);
        } finally {
            if (buf != null) {
                buf.release();
            }
        }
    }
```

@1,@2:MessageToByteEncoder在write方法中处理完msg后,又调了ctx.write()

我们在上面的代码知道,ctx.write()的逻辑就是:

寻找pipeline里面"下一个"handler,然后调用handler的write函数

因此,我们从代码中可以看出,handlerContext和handler之间,通过不断地"重复调用",完成了责任链模式

