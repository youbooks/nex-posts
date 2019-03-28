

```
sudo gem install redis 
```

创建集群:

```
启动六个节点，命令：
redis-server redis.conf
redis-server redis-2.conf	
redis-server redis-3.conf	
redis-server redis-4.conf	
redis-server redis-5.conf	
redis-server redis-6.conf	


redis-server redis-7.conf	

src/redis-trib.rb create --replicas 1 127.0.0.1:6379 127.0.0.1:6380 127.0.0.1:6381 127.0.0.1:6382 127.0.0.1:6383 127.0.0.1:6384
```

查看集群状态:

```
src/redis-trib.rb check 127.0.0.1:6380
```

扩容:

```
src/redis-trib.rb add-node 127.0.0.1:6385 127.0.0.1:6379

后面的6379可以是集群内任意一个

扩容从节点
src/redis-trib.rb add-node --slave --master-id 067e0630d9ef0fe1a501d06bacf68159c422f5db 127.0.0.1:6386 127.0.0.1:6379



(比如:
src/redis-trib.rb add-node --slave --master-id 4aa47d4d3626d8002db3d6b36bf9440517f3b3a4 127.0.0.1:6386 127.0.0.1:6379
)

--slave表示作为从节点加入，--master-id也就是指定他的主节点id。
```

节点添加进去默认是master，但这时虽然添加了，但7008并没有分配到slot，slot是0，所以还要手动做迁移（这也算是redis cluster的一个缺点，不能自动发现新节点，不能自动迁移，全都要手动）。

数据迁移:

```
src/redis-trib.rb reshard 127.0.0.1:6379

16384/4=4096，输入4096

redis-trib.rb reshard 127.0.0.1:6385

然后填入 reciving-node和source-node的id
```

删除节点:

```
./redis-trib.rb del-node 127.0.0.1:6379 b9c7ef68ba4c1ebd807ccd576bf0ed55222b232b
```



参考:https://www.cnblogs.com/lww930/p/5715401.html