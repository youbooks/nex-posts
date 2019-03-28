## nio的基本概念

对于一次IO访问（以read举例），数据会先被拷贝到操作系统内核的缓冲区中，然后才会从操作系统内核的缓冲区拷贝到应用程序的地址空间。所以说，当一个read操作发生时，它会经历两个阶段：

等待数据准备 (Waiting for the data to be ready)

将数据从内核拷贝到进程中 (Copying the data from the kernel to the process)

![](https://ws2.sinaimg.cn/large/006tNbRwgy1fxa7pbv3hhj327y0tsq3w.jpg)



一个阶段是操作系统从网卡缓冲区中拉取数据，另一个阶段是将数据从内核copy到用户进程中。

当有报文进入网卡就会触发中断，kernel把报文送入linux协议栈，报文在协议栈里由下至上经过链路层、ip层、传输层，一直送到socket的内核缓冲区，用户进程调用recv等读接口把报文读出来。

linux下，可以通过设置socket使其变为non-blocking。当对一个non-blocking socket执行读操作时

![](https://ws4.sinaimg.cn/large/006tNbRwgy1fxa7pmxnf9j30hs08pmxo.jpg)



当用户进程发出read操作时，如果kernel中的数据还没有准备好，那么它并不会block用户进程，而是立刻返回一个error。用户进程判断结果是一个error时，它就知道数据还没有准备好，于是它可以再次发送read操作。一旦kernel中的数据准备好了，并且又再次收到了用户进程的system call，那么它马上就将数据拷贝到了用户内存，然后返回。

所以，nio的特点是用户进程不断轮询kernel缓冲区的数据是否ready了。

## IO多路复用

IO multiplexing就是select、poll、epoll，多路复用的好处就在于单个进程就可以同时处理多个网络连接的IO。它的基本原理就是select、poll、epoll这个function会不断的轮询所负责的所有socket，当某个socket有数据到达了，就通知用户进程（其实更准确的说是一个用户进程的selector注册多个socket，任何一个socket中数据准备好了，selector都会知道，当用户进程调用selector的select方法时，会得到所有数据准备好的sockets，用户进程再调用read操作，将数据从kernel拷贝到用户进程）。

![](https://ws2.sinaimg.cn/large/006tNbRwgy1fxa7q3e2jzj30gc08y74u.jpg)



所以，I/O 多路复用的特点是通过一种机制一个进程能同时等待多个文件描述符，而这些文件描述符（套接字描述符）其中的任意一个进入读就绪状态，select()函数就可以返回。

IO多路复用中，对于每个socket都是非阻塞式的，但是由于用户进程需要调用selector的select方法，如果所有注册在selector上的socket都block了（没有数据到达），整个用户进程还是block。只是用户进程是被select这个函数block，而不是被socket IO给block。

IO多路复用的流程大体都是一样的，主要区别就是一句话：任何一个socket中数据准备好了，selector都会知道。select、poll、epoll三种机制就是通过不同的模式来通知selector的。

IO多路复用是同步非阻塞IO。

### select模型

select 函数监视的文件描述符分3类，分别是writefds、readfds、和exceptfds。调用后select函数会阻塞，直到有socket描述符就绪（有数据 可读、可写、或者有except），或者超时（timeout指定等待时间，如果立即返回设为null即可），函数返回。当select函数返回后，可以 通过遍历fdset，来找到就绪的描述符。

```java
int select (int n, fd_set *readfds, fd_set *writefds, fd_set *exceptfds, struct timeval *timeout);

```

在于单个进程能够监视的文件描述符的数量存在最大限制，在Linux上一般为1024，可以通过修改宏定义甚至重新编译内核的方式提升这一限制，但是这样也会造成效率的降低。

### poll模型

与select不同，poll使用一个 pollfd的指针实现。

int poll (struct pollfd *fds, unsigned int nfds, int timeout);
pollfd结构包含了要监视的event和发生的event，不再使用select“参数-值”传递的方式。同时，pollfd并没有最大数量限制（但是数量过大后性能也是会下降）。 和select函数一样，poll返回后，需要轮询pollfd来获取就绪的描述符。

select和poll都需要在返回后，通过遍历文件描述符来获取已经就绪的socket。事实上，同时连接的大量客户端在一时刻可能只有很少的处于就绪状态，因此随着监视的描述符数量的增长，其效率也会线性下降。

### epoll模型

相对于select和poll来说，epoll更加灵活，没有描述符限制。epoll使用一个文件描述符管理多个描述符，将用户关系的文件描述符的事件存放到内核的一个事件表中，这样在用户空间和内核空间的copy只需一次。

```java
//创建一个epoll的句柄，size用来告诉内核这个监听的数目一共有多大
int epoll_create(int size)；
//对指定描述符fd执行op操作。epfd：是epoll_create()的返回值，op：表示op操作，fd：是需要监听的fd，epoll_event：是告诉内核需要监听什么事
int epoll_ctl(int epfd, int op, int fd, struct epoll_event *event)；
//等待epfd上的io事件，最多返回maxevents个事件。
int epoll_wait(int epfd, struct epoll_event * events, int maxevents, int timeout);
```


在 select/poll中，进程只有在调用一定的方法后，内核才对所有监视的文件描述符进行扫描，而epoll事先通过epoll_ctl()来注册一 个文件描述符，一旦基于某个文件描述符就绪时，内核会采用类似callback的回调机制，迅速激活这个文件描述符，当进程调用epoll_wait() 时便得到通知。

epoll的优点

- epoll监视的描述符数量不受限制，它所支持的FD上限是最大可以打开文件的数目

- IO的效率不会随着监视fd的数量的增长而下降。epoll不同于select和poll轮询的方式，而是通过每个fd定义的回调函数来实现的。只有就绪的fd才会执行回调函数。

netty的nio模型就是epoll机制
--------------------- 