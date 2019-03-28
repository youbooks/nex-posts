

## mysql主从数据保持一致性的方法

mysql在CAP方面有着较好的折中，mysql集群主要是通过binlong在主从DB上进行传递来保持同步的。slave的io线程从master读取二进制日志binlog，并在本地保存为中继日志relaylog，然后sql线程读取中继日志relaylog的内容并执行命令，从而保证slave和master数据同步。

![](https://ws3.sinaimg.cn/large/006tNc79gy1fqj4blos6sj319s0tgdpo.jpg)

具体步骤大致如下：

- 1、master验证连接
- 2、master为slave开启主从同步线程
- 3、slave二进制日志binlog的偏移位ssynch告诉master
- 4、master检查ssynch是否小于当前二进制日志binlog偏移位msynch
- 5、如果ssynch小于msynch，则通知slave来取数据
- 6、slave持续从master取数据，直至取完
- 7、当master更新时，master线程被激活，并将二进制日志推送给slave，slave io线程读取网络上的二进制日志binlog
- 8、slave的sql线程执行二进制日志binlog，同步数据

