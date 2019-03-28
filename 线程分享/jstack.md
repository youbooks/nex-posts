```java
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Created by wangwang on 2018/4/4.
 */
public class JstackCase {

    public static Executor executor = Executors.newFixedThreadPool(5);
    public static Object lock = new Object();
    public static void main(String[] args){
        Task task1 = new Task();
        Task task2 = new Task();
        executor.execute(task1);
        executor.execute(task2);
    }

    static class Task implements Runnable {
        @Override
        public void run(){
            synchronized (lock){
                calculate();
            }
        }

        public void calculate(){
            int i = 0;
            while (true){
                i++;
                System.out.println(i);
            }
        }
    }
}
```

![mage-20180404161559](/var/folders/hr/c1f08q_n0msf4gp05cdnc04c0000gn/T/abnerworks.Typora/image-201804041615590.png)

1、上图中可以看出pid为23344的java进程占用了较多的cpu资源；
2、通过`top -Hp 23344`可以查看该进程下各个线程的cpu使用情况；

![mage-20180404161615](/var/folders/hr/c1f08q_n0msf4gp05cdnc04c0000gn/T/abnerworks.Typora/image-201804041616154.png)



上图中可以看出pid为25077的线程占了较多的cpu资源，利用jstack命令可以继续查看该线程当前的堆栈状态。



通过top命令定位到cpu占用率较高的线程之后，继续使用`jstack pid`命令查看当前java进程的堆栈状态



![mage-20180404161637](/var/folders/hr/c1f08q_n0msf4gp05cdnc04c0000gn/T/abnerworks.Typora/image-201804041616377.png)



将该pid转成16进制的值，在thread dump中每个线程都有一个nid，找到对应的nid即可

###### 实例：多线程竞争synchronized锁

![mage-20180404161737](/var/folders/hr/c1f08q_n0msf4gp05cdnc04c0000gn/T/abnerworks.Typora/image-201804041617370.png)

很明显：线程1获取到锁，处于RUNNABLE状态，线程2处于BLOCK状态

1、`locked <0x000000076bf62208>`说明线程1对地址为0x000000076bf62208对象进行了加锁；

2、`waiting to lock <0x000000076bf62208>` 说明线程2在等待地址为0x000000076bf62208对象上的锁；

3、`waiting for monitor entry [0x000000001e21f000]`说明线程1是通过synchronized关键字进入了监视器的临界区，并处于"Entry Set"队列，等待monitor





