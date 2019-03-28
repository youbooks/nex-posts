## 共享锁的实现

入口方法:
![image-20190215223659923](/Users/wangwang/Library/Application Support/typora-user-images/image-20190215223659923.png)

- 1.调用tryAcquireShared(arg)获取锁,如果返回值>=则说明拿到了共享锁 

  这个方法是用户自己实现的, 这里以Semaphore举例说明

  ![image-20190215224233381](/Users/wangwang/Library/Application Support/typora-user-images/image-20190215224233381.png)

  我们知道semaphore的作用主要是并发地获取有限的"资源",其实在内部它用一个volatile修饰的int值来代表"有限资源",

  如上图解析,获取共享锁其实就是对这个int做减法,所得大于零则说明获取成功

  

- 2.如果共享锁已经被被抢光光,那么会执行doAcquireShared(arg)

![image-20190215224648445](/Users/wangwang/Library/Application Support/typora-user-images/image-20190215224648445.png)

为了更好地理解这个过程,我们模拟一个场景:此时共享锁都被抢光,然后第一个线程进来
 - 1.创建一个Node,并放入同步队列  ![image-20190215225031587](/Users/wangwang/Library/Application Support/typora-user-images/image-20190215225031587.png)

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g07i1e12saj30a605674b.jpg)

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g07hx1p3f7j30w00g8mzf.jpg)

  此时:![](https://ww1.sinaimg.cn/large/007i4MEmgy1g07i22l1w5j30fi05caac.jpg)

- 2.开始**自旋**,如果前置节点是head,才能再次检测tryAcquireShared()

  ![](https://ww1.sinaimg.cn/large/007i4MEmgy1g07iaq2yipj30h8050mxg.jpg)

- 4.获取成功,调用setHeadAndPropagate(node, r),顾名思义这个函数的作用是设置head,以及"传播"给后置节点(如果有后置节点,那么会唤醒unpark它),我们放在后面讲解

- 5.如果前置节点不是head , 或者tryAcquireShared()失败,则进入shouldParkAfterFailedAcquire()判断,如果返回true,那么就将当前节点挂起.  我们这里假设自旋失败,来看shouldParkAfterFailedAcquire()发生了什么:

  ![](https://ww1.sinaimg.cn/large/007i4MEmgy1g07if2gjwnj31aw0f2jun.jpg)

  所以我们可以推想一下,如果获取锁地线程一直不释放锁,那么后续进入同步队列的所有线程或者说node的等待状态都会变为SIGNAL. 这里其实是一个伏笔,先留一个问题:为什么waitStatus=SIGNAL这么重要?

现在的队列是这样:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g07iku3l81j30ns062aax.jpg)

接下来,我们假设有锁被释放

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g07inlp0jaj30t8080gmr.jpg)

AQS中执行releaseShared(),还是以semaphore为例子,看看怎么实现tryReleaseShared()的

![image-20190215231546570](/Users/wangwang/Library/Application Support/typora-user-images/image-20190215231546570.png)

原理很简单,做一个加法,把之前减掉的int加回来,然后用CAS来设置为current的值解决了并发问题

然后进入doReleaseShared()

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g07haytpr9j31ay0m443j.jpg)

这里回答了上面的问题,只有ws==Node.SIGNAL的时候,才会唤醒后置节点

OK,唤醒后继续执行doAcquireShared(arg)里面的死循环,此时的状态:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g07iqnl8hsj30m608qmy9.jpg)

这一次自旋当然会成功,然后执行setHeadAndPropagate()

![](https://0d077ef9e74d8.cdn.sohucs.com/ri9YwD2_jpg)

首先重置了head节点,然后又调用doReleaseShared()来唤醒后置节点,此时的状态:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g07iuyxg3hj30fa07a3z1.jpg)

Node2被唤醒,恢复自旋

插一句:

我们看到doReleaseShared()这个方法除了在用户主动release的时候调用,还会在节点获取到共享锁的时候被调用

> tryAcquireShared(arg)成功后,调用setHeadAndPropagate(node, r),然后调用doReleaseShared()唤醒后面的节点

因为这个方法的作用是:**唤醒后节点**

但是由于调用它的地方有两个,所以我当时还发了贴来讨论它的命名是否不准确…(见下图)

![image-20190215223109781](/Users/wangwang/Library/Application%20Support/typora-user-images/image-20190215223109781.png)



画了一个流程图来总结共享锁的获取和释放:

- 获取:

  ![](https://ww1.sinaimg.cn/large/007i4MEmgy1g088lah416j30ye0vajxo.jpg)

- 释放

  ![](https://ww1.sinaimg.cn/large/007i4MEmgy1g088lmvm91j30lc0o241r.jpg)



## 独占锁的实现

入口方法:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g088pv5n5qj30zy066q46.jpg)

addWaiter()方法已经在上面解析过了,这里没有任何变化

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g088rdqtu6j30z20mq43z.jpg)

- 1.如果当前节点的前置节点为head,那么再次检测当前是否可以获取到锁(tryAcquire()),如果获取成功,只会重置head,不会
- 2.将前置节点的等待状态置为SIGNAL,并park当前节点

注意:这里和上面的共享锁做一个对比,差异其实就一点:共享锁在自旋成功后会重置head并调用setHeadAndPropagate()唤醒后置节点让它们继续尝试获取锁,但是独占锁只会重置head并不会唤醒后置节点,只会在独占锁被释放的时候来唤醒后置节点

归纳一下:

- 1.共享锁会在两个地方唤醒后置节点: 1).获取共享锁成功后 2).共享锁释放后
- 2.独占锁只会在一个地方唤醒后置节点: 独占锁被释放后
- 3.要唤醒后置节点,必须要求前置节点的waitStatus为signal,当一个节点自旋失败后,会调用shouldParkAfterFailedAcquire()设置前置的等待状态为signal(等待状态变为signal只会发生在这时)
- 4.因为唤醒的都是head,所以有必要留意head的变化:最开始head是一个空node,然后head会被获取了锁的节点所替代(waitStatus都为signal)

释放:

![image-20190216222028658](/Users/wangwang/Library/Application Support/typora-user-images/image-20190216222028658.png)

- 1.tryRelease()由用户自己实现,这里给出ReentrantLock的实现

  ![image-20190216222253646](/Users/wangwang/Library/Application Support/typora-user-images/image-20190216222253646.png)

  因为ReentrantLock的tryAcquire()会用CAS来累加state的值,所以这里是减去,为什么这里没有用CAS锁呢?

  因为只有是ExclusiveOwnerThread才能重置state的值,换句话说,只有一个线程不需要考虑线程安全的问题

- 2.如果释放成功就唤醒后置节点让它来抢占被释放的锁

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g09q7xqkq2j30zi0w6agj.jpg)



## Condition

AQS中使用条件队列+同步队列结合的方式实现了我们日常使用中的Condition

### 使用

我们先看一个对Condition的经典应用,然后跟着这个例子来剖析Condition的实现

在JUC的LinkedBlockingQueue中,有这样一把锁和这样一个Condition

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g08nmvauhzj310805u3zt.jpg)

put方法得实现如下:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0gtxq0bpcj31440miaoo.jpg)

入队后,count加一(原来是-1),所以加一成功就是0,然后调用signalNotEmpty()—其实c=0并不代表此时队列就有可读的节点了,只是这时候可以将条件队列中的节点转移到同步队列,为后面的take做准备了

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g08nqng21sj30y60a2jsw.jpg)

- 1.先获取takeLock
- 2.notEmpty这个Condition被signal,说明此时队列不是空的,可以被消费

再来看take:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0gtx64bqjj30xm0m8n93.jpg)

如果count为0,那么所有进入这个方法的线程都会进入条件等待队列,等待signal()的调用

### await

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g09rxhk0zfj31b60lkn3o.jpg)

- 1.生成新的node(这里的Node的数据结构和同步队列的一样,因为后面有转移操作),并将它置于队尾,并释放当前获得的锁

- 2.如果node一直未被转移到同步队列,则一直被挂起,注意这里为什么会有这样一个while循环,其实是因为signal的

  ()的时候会将node转移到同步队列,从而跳出这个循环

- 3.跳出循环后,调用acquireQueued()函数,从前面一直看到这里的同学一定知道这个函数的作用了,它会让当前节点自旋,去尝试获取独占锁,获取到锁后,继续执行后面的逻辑

我们还是利用LinkedBlockingQueue来做情景模拟,假设此时队列中没有任何可以被消费的节点,那么count<=0,进入take()方法的线程都会被加入到条件队列,此时条件队列有两个节点:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g09qcmlfdbj30l404wdgc.jpg)

而同步队列为空

### signal

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g08n6jv035j30uu07q75i.jpg)

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g08n71mv00j30pc09sgn3.jpg)

![image-20190216224109126](/Users/wangwang/Library/Application Support/typora-user-images/image-20190216224109126.png)

当put()方法被调用,会执行notEmpty.signal(),这时候条件队列中的节点会被转移到同步队列中,现在的情景如下:

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0aprj762kj30ku0bajsj.jpg)

Node1被转移,并且自旋尝试去获取锁

