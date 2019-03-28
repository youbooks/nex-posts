信号量（Semaphore）是一个计数器，用来保护对一个或多个共享资源的访问。

### 信号量原理

你可以认为信号量是一个可以递增或递减的计数器。你可以初始化一个信号量的值为5，此时这个信号量可最大连续减少5次，直到计数器为0。当计数器为0时，你可以让其递增5次，使得计数器值为5。在我们的例子中，信号量的计数器始终限制在[0~5]之间。

显然，信号量并不仅仅是计数器。当计数器值为0时，它们可以使线程等待，即它们是具有计数器功能的锁。

就多线程而言，当一个线程要访问共享资源（由信号量保护）时，首先，它必须获得信号量。如果信号量的内部计数器大于0时，信号量递减计数器，并允许访问共享资源。否则，如果信号量的计数器为0，则信号量将线程置于休眠状态，直到计数器大于0。计数器中的值为0意味着所有共享资源都被其他线程使用，因此希望使用共享资源的线程必须等到有线程空闲（释放信号量）。

*当一个线程完成了共享资源的使用时，它必须释放信号量，以便其他线程能够访问共享资源。该操作增加信号量的内部计数器。*

### 如何使用信号量

*PrinterQueue.java*
 该类表示一个打印机队列，它们控制从3个打印机中选择一个空闲的打印机打印，并在打印过程中加锁。当完成打印任务时，打印机被释放，可用于新的打印任务。
 该类有2个方法getPrinter()和releasePrinter() ，分别是负责获取空闲的打印机和释放打印机。另外一个方法printJob() 实际上才是核心工作，获取打印机，执行打印任务，然后释放打印机。
 它使用以下2个变量：
 semaphore：这个变量跟踪在任何时间点使用打印机的数量。
 printerlock：在3个可用的打印机中检查/获取空闲打印机时，锁定打印机池。

```
class PrinterQueue {
    //保存可用打印机的数量
    private final Semaphore semaphore;

    //当检查/获取空闲打印机时加锁
    private final Lock printerLock;

    //代表空闲的打印机池
    private boolean freePrinters[];

    public PrinterQueue() {
        semaphore = new Semaphore(3);
        freePrinters = new boolean[3];
        for (int i = 0; i < 3; i++) {
            freePrinters[i] = true;
        }
        printerLock = new ReentrantLock();
    }

    public void printJob(Object document) {
        try {
            //递减信号量计数器，标记一台打印机忙碌
            semaphore.acquire();

            //获取一台空闲打印机
            int assignedPrinter = getPrinter();

            //执行打印任务
            Long duration = (long) (Math.random() * 10000);
            System.out.println(Thread.currentThread().getName()
                    + ": Printer " + assignedPrinter
                    + " : Printing a Job during " + (duration / 1000)
                    + " seconds :: Time - " + new Date());
            Thread.sleep(duration);

            //打印任务完成; 释放打印机给其他线程.
            releasePrinter(assignedPrinter);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            System.out.printf("%s: The document has been printed\n", Thread
                    .currentThread().getName());

            //递增信号量计数器
            semaphore.release();
        }
    }

    //获取空闲打印机
    private int getPrinter() {
        int foundPrinter = -1;
        try {
            //获取锁，使得只有一个线程访问
            printerLock.lock();

            //检查哪台打印机空闲
            for (int i = 0; i < freePrinters.length; i++) {
                //找到空闲打印机，标记为忙碌
                if (freePrinters[i]) {
                    foundPrinter = i;
                    freePrinters[i] = false;
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            //释放锁，使得其他线程可以检查是否有空闲打印机
            printerLock.unlock();
        }
        return foundPrinter;
    }

    //释放打印机
    private void releasePrinter(int i) {
        printerLock.lock();
        //标记打印机为空闲
        freePrinters[i] = true;
        printerLock.unlock();
    }
}
```

*PrintingJob.java*
 该类代表可以提交独立的打印工作给打印机。

```
class PrintingJob implements Runnable {
    private PrinterQueue printerQueue;

    public PrintingJob(PrinterQueue printerQueue) {
        this.printerQueue = printerQueue;
    }

    @Override
    public void run() {
        System.out.printf("%s: Going to print a document\n",
                Thread.currentThread().getName());
        printerQueue.printJob(new Object());
    }
}
```

*测试代码*

```
public class SemaphoreExample {
    public static void main(String[] args) {
        PrinterQueue printerQueue = new PrinterQueue();
        Thread thread[] = new Thread[10];
        for (int i = 0; i < 10; i++) {
            thread[i] = new Thread(new PrintingJob(printerQueue), "Thread " + i);
        }
        for (int i = 0; i < 10; i++) {
            thread[i].start();
        }
    }
}
```

执行结果

```
Thread 0: Going to print a document
Thread 9: Going to print a document
Thread 8: Going to print a document
Thread 7: Going to print a document
Thread 6: Going to print a document
Thread 5: Going to print a document
Thread 4: Going to print a document
Thread 3: Going to print a document
Thread 2: Going to print a document
Thread 1: Going to print a document
Thread 9: Printer 1 : Printing a Job during 4 seconds :: Time - Sat Aug 12 10:14:39 CST 2017
Thread 0: Printer 0 : Printing a Job during 1 seconds :: Time - Sat Aug 12 10:14:39 CST 2017
Thread 8: Printer 2 : Printing a Job during 2 seconds :: Time - Sat Aug 12 10:14:39 CST 2017
Thread 0: The document has been printed
Thread 7: Printer 0 : Printing a Job during 7 seconds :: Time - Sat Aug 12 10:14:41 CST 2017
Thread 8: The document has been printed
Thread 6: Printer 2 : Printing a Job during 4 seconds :: Time - Sat Aug 12 10:14:41 CST 2017
Thread 9: The document has been printed
Thread 5: Printer 1 : Printing a Job during 1 seconds :: Time - Sat Aug 12 10:14:43 CST 2017
Thread 5: The document has been printed
Thread 4: Printer 1 : Printing a Job during 8 seconds :: Time - Sat Aug 12 10:14:44 CST 2017
Thread 6: The document has been printed
Thread 3: Printer 2 : Printing a Job during 5 seconds :: Time - Sat Aug 12 10:14:46 CST 2017
Thread 7: The document has been printed
Thread 2: Printer 0 : Printing a Job during 2 seconds :: Time - Sat Aug 12 10:14:49 CST 2017
Thread 2: The document has been printed
Thread 1: Printer 0 : Printing a Job during 9 seconds :: Time - Sat Aug 12 10:14:51 CST 2017
Thread 3: The document has been printed
Thread 4: The document has been printed
Thread 1: The document has been printed
```

### 何时使用二进制信号量

很明显，二进制信号量的值可以是0或1。这意味着二进制信号量保护对单个共享资源的访问，因此信号量的内部计数器只能接受值1或0。
 因此，每当你有保护对多个线程访问单个资源的要求时，就可以使用二进制信号量。

### 如何使用二进制信号量

为了说明二进制信号量的用法，我们将实现一个打印队列，可以由并发任务来打印它们的作业。此打印队列将受到二进制信号量的保护，因此每次只有一个线程执行打印作业。

*PrinterQueue.java*
 该类表示打印机队列。请注意，我们将值1作为该信号量构造函数的参数传递，因此你正在创建一个二进制信号量。

```
public class PrinterQueue {
    private final Semaphore semaphore;

    public PrinterQueue() {
        semaphore = new Semaphore(1);
    }

    public void printJob(Object document) {
        try {
            semaphore.acquire();
            Long duration = (long) (Math.random() * 10000);
            System.out.println(Thread.currentThread().getName()
                    + ": PrintQueue: Printing a Job during " + (duration / 1000)
                    + " seconds :: Time - " + new Date());
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            System.out.printf("%s: The document has been printed\n",
                    Thread.currentThread().getName());
            semaphore.release();
        }
    }
}
```

*执行结果*

```
Thread 0: Going to print a document
Thread 9: Going to print a document
Thread 8: Going to print a document
Thread 7: Going to print a document
Thread 6: Going to print a document
Thread 5: Going to print a document
Thread 4: Going to print a document
Thread 3: Going to print a document
Thread 2: Going to print a document
Thread 1: Going to print a document
Thread 0: PrintQueue: Printing a Job during 7 seconds :: Time - Sat Aug 12 09:14:23 CST 2017
Thread 0: The document has been printed
Thread 9: PrintQueue: Printing a Job during 5 seconds :: Time - Sat Aug 12 09:14:30 CST 2017
Thread 9: The document has been printed
Thread 8: PrintQueue: Printing a Job during 6 seconds :: Time - Sat Aug 12 09:14:35 CST 2017
Thread 8: The document has been printed
Thread 7: PrintQueue: Printing a Job during 8 seconds :: Time - Sat Aug 12 09:14:42 CST 2017
Thread 7: The document has been printed
Thread 6: PrintQueue: Printing a Job during 0 seconds :: Time - Sat Aug 12 09:14:50 CST 2017
Thread 6: The document has been printed
Thread 5: PrintQueue: Printing a Job during 4 seconds :: Time - Sat Aug 12 09:14:51 CST 2017
Thread 5: The document has been printed
Thread 4: PrintQueue: Printing a Job during 6 seconds :: Time - Sat Aug 12 09:14:55 CST 2017
Thread 4: The document has been printed
Thread 3: PrintQueue: Printing a Job during 0 seconds :: Time - Sat Aug 12 09:15:01 CST 2017
Thread 3: The document has been printed
Thread 2: PrintQueue: Printing a Job during 3 seconds :: Time - Sat Aug 12 09:15:02 CST 2017
Thread 2: The document has been printed
Thread 1: PrintQueue: Printing a Job during 4 seconds :: Time - Sat Aug 12 09:15:05 CST 2017
Thread 1: The document has been printed
```

参考
 1.[Binary Semaphore Tutorial and Example](https://link.jianshu.com?t=http://howtodoinjava.com/core-java/multi-threading/binary-semaphore-tutorial-and-example/)
 2.[Control concurrent access to multiple copies of a resource using Semaphore](https://link.jianshu.com?t=http://howtodoinjava.com/core-java/multi-threading/control-concurrent-access-to-multiple-copies-of-a-resource-using-semaphore/)