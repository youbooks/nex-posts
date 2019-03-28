## 实例:

org.apache.rocketmq.broker.transaction.TransactionalMessageCheckService

```java
private final AtomicBoolean started = new AtomicBoolean(false);
......   

@Override
    public void start() {
        if (started.compareAndSet(false, true)) {
            super.start();
            this.brokerController.getTransactionalMessageService().open();
        }
   }
```

在这个rocketmq的例子中,作者使用CAS来防止start()方法被多次调用

一开始started的值是false,第一个执行CAS操作的线程compareAndSet(false, true),把started值设为true

后面执行CAS操作的线程走到这就不会往下走了

## CAS原理:

CAS：现代的处理器都包含对并发的支持，其中最通用的方法就是比较并交换（compare and swap），简称CAS。
CAS是非阻塞的同步机制，锁解决的是阻塞的同步机制，但是锁需要借助操作系统，线程的挂起和恢复都有很大的开销。而CAS在不释放cpu的情况下进行同步，这是CAS的功能。
CAS 操作包含三个操作数 —— 内存位置（V）、预期原值（A）和新值(B)。如果内存位置的值与预期原值相匹配，那么处理器会自动将该位置值更新为新值，否则，处理器不做任何操作。

ABA问题：因为CAS需要操作值得时候，会检查值有没有变化，如果没有变化，像上面提到这回进行跟新操作，假设有这么一种情况原来是A变成的B，又变成了A,那么CAS 进行检查的时候就会发现他们没有变化，但实际上是变化了的，这便会存在问题，本来不应该跟新的，结果却跟新了。ABA问题的解决思路是加版本号，即在变量前追加版本号，每次跟新的时候版本号加一，如A—>B----→A  就会变成1A---->2A---→3A.这样就很好的解决了ABA 问题

Atom原子类
JVM是支持CAS的，体现在我们常用的Atom原子类，拿AtomicInteger分析一下源码

![](https://ws4.sinaimg.cn/large/006tNbRwgy1fxawtyie7pj30s40bs0sv.jpg)

对AtomicInteger进行+1操作，循环里，会将当前值和+1后的目标值传入compareAndSet，直到成功才跳出方法。compareAndSet是不是很熟悉呢，接着来看看它的代码。

![](https://ws2.sinaimg.cn/large/006tNbRwgy1fxawu6wkk9j30uc0bk74r.jpg)

compareAndSet调用了unsafe.compareAndSwapInt，这是一个native方法，原理就是调用硬件支持的CAS方法。看懂这个应该就能明白Atom类的原理，其他方法的实现是类似的。
