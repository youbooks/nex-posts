NioEventLoop模型
需要注意的是，这个模型已经很像Netty的Reactor模型，但是依然不是Netty的真实模型，Netty并没有使用线程池异步处理请求，而且在每个subReactor中串行的处理请求。

netty的reactor模型：

![](https://ws2.sinaimg.cn/large/006tNbRwgy1fxa8dy051qj30gb0d1aad.jpg)



或模型2：

![](https://ws1.sinaimg.cn/large/006tNbRwgy1fxa8e3o8z5j30ie0bmgly.jpg)



在netty中，boss reactor一般只有一个，负责响应client的连接请求，并建立连接，它使用一个NioEventLoop；subReactor可以有一个或者多个，每个subReactor都会在一个独立线程中执行，并且维护一个独立的NioEventLoop。

reactor就是事件循环器EventLoop，EventLoop就是reactor。我们常用的是NioEventLoop。（个人认为）EventLoop是netty设计最为精巧的组件，也是netty最核心的组件。

一般开发过程中把reactor分为boss reactor和worker reactor，boss reactor一般只有一个，worker reactor一般是 cpu的核数 * 2个。boss和worker这只是人为划分，并不代表boss reactor和worker reactor是两个类实例的对象。实际上，boss reactor和worker reacto对应的类都是NioEventLoop，所有流程都是一样的，只是NioEventLoop对应的channel不同，使得boss reactor处理accept事件，将新连接封装成channel对象扔给worker reactor，worker reactor处理连接的读写事件。

NioEventLoop在netty4.0之前分别被称为NioServerBoss和NioWorker。

NioEventLoop的类层次结构：

![](https://ws1.sinaimg.cn/large/006tNbRwgy1fxa8ecj9ylj30xt0u0q3r.jpg)

NioEventLoop 继承于 SingleThreadEventLoop, 而 SingleThreadEventLoop 又继承于 SingleThreadEventExecutor. SingleThreadEventExecutor 是 Netty 中对本地线程的抽象, 它内部有一个 Thread thread 属性, 存储了一个本地 Java 线程。从名字来看，NioEventLoop是一个单线程的，所以，一个 NioEventLoop 其实和一个特定的线程绑定, 并且在其生命周期内, 绑定的线程都不会再改变。

而NioEventLoop在顶层也实现了ExecutorService接口，表示可以向NioEventLoop提交task，由NioEventLoop进行调度执行。实际上，NioEventLoop维护了一个任务队列，可以执行一些定时任务和优先级任务。NioEventLoop 肩负着两种任务, 第一个是作为 IO 线程, 执行与 Channel 相关的 IO 操作, 包括 调用 select 等待就绪的 IO 事件、读写数据与数据的处理等; 而第二个任务是作为任务队列, 执行 taskQueue 中的任务, 例如用户调用 eventLoop.schedule 提交的定时任务也是这个线程执行的。并且，netty的write操作也是通过task来实现的，这就是NioEventLoop设计精巧之处。

NioEventLoop是一个单线程的无限循环，启动时首先会判断是否在循环中，如果在，就直接向NioEventLoop的taskQueue添加task；如果不在，则先启动线程，开始循环，然后向taskQueue添加task。

```java
public void execute(Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
 
        boolean inEventLoop = inEventLoop();
  			// 判断是否在循环中
        if (inEventLoop) {
          // 直接提交task
            addTask(task);
        } else {
          // 先启动线程，开始循环
            startThread();
            addTask(task);
            if (isShutdown() && removeTask(task)) {
                reject();
            }
        }
 
        if (!addTaskWakesUp && wakesUpForTask(task)) {
            wakeup(inEventLoop);
        }
    }

```
启动线程其实就是在单线程中执行NioEventLoop的run方法，run方法就是一个无限循环
```java
protected void run() {
        for (;;) {
            try {
              // 第一步，轮询io事件
                switch (selectStrategy.calculateStrategy(selectNowSupplier, hasTasks())) {
                    case SelectStrategy.CONTINUE:
                        continue;
                    case SelectStrategy.SELECT:
                        select(wakenUp.getAndSet(false));
                        if (wakenUp.get()) {
                            selector.wakeup();
                        }
                    default:
                        // fallthrough
                }
 
                cancelledKeys = 0;
                needsToSelectAgain = false;
                final int ioRatio = this.ioRatio;
                if (ioRatio == 100) {
                    try {
                      // 第二步，处理io事件
                        processSelectedKeys();
                    } finally {
                        // 第三步，处理taskQueue中的任务
                        runAllTasks();
                    }
                } else {
                    final long ioStartTime = System.nanoTime();
                    try {
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        final long ioTime = System.nanoTime() - ioStartTime;
                        runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
            // Always handle shutdown even if the loop processing threw an exception.
            try {
                if (isShuttingDown()) {
                    closeAll();
                    if (confirmShutdown()) {
                        return;
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
        }
    }

```

run方法中的代码看起来复杂，但其实主要的就只有三步：

1. select操作：轮询注册到reactor线程对应的selector上的所有channel的IO事件
2. processSelectedKeys操作：处理轮询到的IO事件
3. runAllTasks操作：处理任务队列的task

![](https://ws3.sinaimg.cn/large/006tNbRwgy1fxa8ftojo0j312a0u0aeo.jpg)



### 轮询IO事件

```java
select(wakenUp.getAndSet(false));
if (wakenUp.get()) {
      selector.wakeup();
}
```


在进行select操作过程中，wakenUp 表示是否应该唤醒正在阻塞的select操作，可以看到netty在进行一次新的loop之前，都会将wakeUp 被设置成false，标志新的一轮loop的开始。

其实轮询IO事件在jdk中很简单，就是selector.selectNow()或selector.select(timeout)方法，但是netty处理得非常麻烦，主要是因为netty需要处理任务队列中的任务和“丧心病狂”的性能优化。

因此，结束当前loop的轮询的条件有：

定时任务截止事时间快到了，中断本次轮询

轮询过程中发现有任务加入，中断本次轮询

timeout时间内select到IO事件（select会阻塞，但是外部线程在execute任务会调用wakeup方法唤醒selector的阻塞）

用户主动唤醒（直接调用wakeup方法）

此外，netty还解决了jdk的一个nio bug，该bug会导致Selector一直空轮询，最终导致cpu 100%，nio server不可用。netty使用rebuildSelector来fix空轮询bug。

netty 会在每次进行 selector.select(timeoutMillis) 之前记录一下开始时间currentTimeNanos，在select之后记录一下结束时间，判断select操作是否至少持续了timeoutMillis秒，如果持续的时间大于等于timeoutMillis，说明就是一次有效的轮询，重置selectCnt标志，否则，表明该阻塞方法并没有阻塞这么长时间，可能触发了jdk的空轮询bug，当空轮询的次数超过一个阀值的时候，默认是512，就开始重建selector。



### 处理轮询到的IO事件

处理IO事件的主体代码如下：

```java

private void processSelectedKeysOptimized(SelectionKey[] selectedKeys) {
        for (int i = 0;; i ++) {
          // 取出轮询到的SelectionKey
            final SelectionKey k = selectedKeys[i];
            if (k == null) {
                break;
            }
            // null out entry in the array to allow to have it GC'ed once the Channel close
          // 可以立即gc回收对象
            selectedKeys[i] = null;

            final Object a = k.attachment();
    					// attachment取出来的值AbstractNioChannel是对象，然后处理channel
            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }
    					// 判断是否需要再次轮询
            if (needsToSelectAgain) {
                for (;;) {
                    i++;
                    if (selectedKeys[i] == null) {
                        break;
                    }
                    selectedKeys[i] = null;
                }
     
                selectAgain();
                
                selectedKeys = this.selectedKeys.flip();
                i = -1;
            }
        }
    }
```
从上面方法的名字就可以看出来，这是一个被优化过的处理轮询的方法。this.selectedKeys是一个set，与selector绑定，selector在调用select()族方法的时候，如果有IO事件发生，就会往this.selectedKeys中塞相应的selectionKey。而selectedKeys内部维护了两个SelectionKey[]数组，重写了set的add方法，在add的时候实际上是往数组里面塞SelectionKey。而在遍历时只用遍历数组而不是遍历set，可见netty对性能的极致优化。

处理轮询到的IO事件也主要是三步：

取出轮询到的SelectionKey

取出与客户端交互的channel对象，处理channel

判断是否需要再次轮询

第一步上面已经说了，this.selectedKeys与selector绑定，如果有IO事件发生，就会往this.selectedKeys中塞相应的selectionKey。然后遍历selectedKeys，取出轮询到的SelectionKey。

第二步取出selectionKey中的attachment对象，这里attachment一般是AbstractNioChannel对象，AbstractNioChannel对象代表每一条连接，拿到这个对象就可以获取每条连接的所有信息了。然后来看看selectionKey是在哪里设置这个对象。

在AbstractNioChannel中有一个doRegister方法，这里将jdk的channel注册到selector上去，并且将自身设置到attachment上。这样再jdk轮询出某条SelectableChannel有IO事件发生时，就可以直接取出AbstractNioChannel了。

selectionKey = javaChannel().register(eventLoop().selector, 0, this);
第二步最重要的就是处理channel，也就是真正到了处理轮询到的IO事件了，主体代码如下：

```java

private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
        // 先去掉一些无关代码
  			// ……

        try {
            int readyOps = k.readyOps();
            // 首先完成连接操作
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                int ops = k.interestOps();
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);
     
                unsafe.finishConnect();
            }
     
            // 处理write事件的flush
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                ch.unsafe().forceFlush();
            }
     
            // 处理读和新连接的accept事件
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
                unsafe.read();
                if (!ch.isOpen()) {
                    // Connection already closed - no need to handle write.
                    return;
                }
            }
        } catch (CancelledKeyException ignored) {
            unsafe.close(unsafe.voidPromise());
        }
    }
```

从这里也可以看出来netty所有关于IO操作都是通过内部的Unsafe来实现的。

processSelectedKey是一个很复杂的过程，简单讲解一下，也分成三步

首先在读写之前都要先调用finishConnect，来确保与客户端连接上。这个过程最终会传递给channelHandler的channelActive方法，因此可以通过channelActive来验证有多少客户端在线。

接下来是处理write事件的flush，注意，我们的write不是在这里做的，真正的write一般是封装成task去执行的。

第三步是处理读和新连接的accept事件。netty将新连接的accept也当做一次read。对于boss NioEventLoop来说，新连接的accept事件在read的时候通过他的pipeline将连接扔给一个worker NioEventLoop处理；而worker NioEventLoop处理读事件，是通过他的pipeline将读取到的字节流传递给每个channelHandler来处理。

接下来是判断是否再次轮询，是根据needsToSelectAgain来判断的，当needsToSelectAgain为true，表示需要再次轮询。那么最重要的是看needsToSelectAgain什么时候为true。在NioEventLoop类中，只有在cancel方法中将needsToSelectAgain设置为true。而在AbstractNioChannel的doDeregister调用了eventLoop的cancel方法。

```java

protected void doDeregister() throws Exception {
        eventLoop().cancel(selectionKey());
    }
```
这个方法是在channel从selector上移除的时候，调用cancel函数将key取消，并且当被去掉的key到达 CLEANUP_INTERVAL = 256 的时候，设置needsToSelectAgain为true。

即netty每隔256次channel断线，重新清理一下selectionKey，保证现存的SelectionKey及时有效。

总结一下处理轮询到的IO事件的过程就是：

netty使用数组替换掉jdk原生的HashSet来保证IO事件的高效处理，每个SelectionKey上绑定了netty类AbstractChannel对象作为attachment，在处理每个SelectionKey的时候，就可以找到AbstractChannel，然后通过pipeline的方式将处理串行到ChannelHandler，回调到用户channelHandler的方法。





### 处理任务队列的task
NioEventLoop三步曲的最后一步了，处理任务队列的task，按照惯例，先把代码的主流程贴出来。

```java
protected boolean runAllTasks(long timeoutNanos) {
        fetchFromScheduledTaskQueue();
        Runnable task = pollTask();
        if (task == null) {
            afterRunningAllTasks();
            return false;
        }

        final long deadline = ScheduledFutureTask.nanoTime() + timeoutNanos;
        long runTasks = 0;
        long lastExecutionTime;
        for (;;) {
            safeExecute(task);
     
            runTasks ++;
     
            if ((runTasks & 0x3F) == 0) {
                lastExecutionTime = ScheduledFutureTask.nanoTime();
                if (lastExecutionTime >= deadline) {
                    break;
                }
            }
     
            task = pollTask();
            if (task == null) {
                lastExecutionTime = ScheduledFutureTask.nanoTime();
                break;
            }
        }
     
        afterRunningAllTasks();
        this.lastExecutionTime = lastExecutionTime;
        return true;
    }
```
这个方法就是尽量在timeoutNanos时间内，将所有的任务都取出来run一遍。

而这个时间是怎么定的呢？
```java
final long ioStartTime = System.nanoTime();
try {
  processSelectedKeys();
} finally {
  // Ensure we always run tasks.
  final long ioTime = System.nanoTime() - ioStartTime;
  runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
}
```
processSelectedKeys是处理轮询到的IO事件，ioRatio设定的是50，那么ioTime * (100 - ioRatio) / ioRatio = ioTime * (100 - 50) / 50 = ioTime，netty是希望最多在等同于处理IO事件的时间去处理task任务，严格控制了内部队列的执行时间。

NioEventLoop执行task的过程，同样可以分成几步：

从scheduledTaskQueue转移定时任务到taskQueue

计算本次任务循环的截止时间

执行任务

执行完任务后的工作

从上面可以看到NioEventLoop中至少有两种队列，taskQueue和scheduledTaskQueue。

EventLoop是一个Executor，因此用户可以向EventLoop提交task。在execute方法中，当EventLoop处于循环中或启动了循环后都会通过addTask(task)向EventLoop提交任务。EventLoop内部使用一个taskQueue将task保存起来。
```java
protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
   return new LinkedBlockingQueue<Runnable>(maxPendingTasks);
}
```
taskQueue是一个有界阻塞队列，在reactor线程内部用单线程来串行执行，最终真正执行的地方就是这个runAllTasks方法。

taskQueue最大的应用场景就是用户在channelHandler中获取到channel，然后通过channel.write()数据，这里会把write操作封装成一个WriteTask，然后通过eventLoop.execute(task)执行，实际上是给EventLoop提交了一个task，加入到taskQueue队列中
```java
private void write(Object msg, boolean flush, ChannelPromise promise) {
    AbstractChannelHandlerContext next = findContextOutbound();
    final Object m = pipeline.touch(msg, next);
  	// executor就是eventLoop
    EventExecutor executor = next.executor();
    if (executor.inEventLoop()) {
      if (flush) {
        next.invokeWriteAndFlush(m, promise);
      } else {
        next.invokeWrite(m, promise);
      }
    } else {
      // inEventLoop返回false，执行这里的操作
      AbstractWriteTask task;
      if (flush) {
        task = WriteAndFlushTask.newInstance(next, m, promise);
      }  else {
        task = WriteTask.newInstance(next, m, promise);
      }
      // 将write操作封装成WriteTask，然后像eventLoop提交task
      safeExecute(executor, task, promise, m);
   }
}
```
同时，EventLoop也是一个ScheduledExecutorService，这意味着用户可以通过ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit)方法向EventLoop提交定时任务。因此，EventLoop内部也维护了一个优先级队列scheduledTaskQueue来保存提交的定时任务。

知道了NioEventLoop内部的任务队列后，再来看执行task的过程。

第一步，是将到期的定时任务转移到taskQueue中，只有在当前定时任务的截止时间已经到了，才会取出来。

然后第二步计算本次任务循环的截止时间deadline。

第三步真正去执行任务，先执行task的run方法，然后将runTasks加一，每执行完64（0x3F）个任务，就判断当前时间是否超过deadline，如果超过，就break，如果没有超过，就继续执行。

需要注意的是，这里如果任务没执行完break掉了，afterRunningAllTasks后，NioEventLoop就会重新开始一轮新的循环，没完成的任务仍然在taskQueue中，等待runAllTasks的时候去执行。

最后一步是afterRunningAllTasks，执行完所有任务后需要进行收尾，相当于一个钩子方法，可以作统计用。
最后总结一下处理任务队列的task的过程就是：

eventLoop是一个Executor，可以调用execute给eventLoop提交任务，NioEventLoop会在runAllTasks执行。NioEventLoop内部分为普通任务和定时任务，在执行过程中，NioEventLoop会把过期的定时任务从scheduledTaskQueue转移到taskQueue中，然后执行taskQueue中的任务，同时每隔64个任务检查是否该退出任务循环。