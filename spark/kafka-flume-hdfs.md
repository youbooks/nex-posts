https://blog.csdn.net/feinifi/article/details/73929015

https://www.cnblogs.com/itdyb/p/6270893.html

./zookeeper-server-start.sh /Users/wangwang/Downloads/kafka/config/zookeeper.properties 

./kafka-server-start.sh /Users/wangwang/Downloads/kafka/config/server.properties 

./kafka-topics.sh --create --zookeeper 127.0.0.1:2181 --replication-factor 1 --partitions 1 --topic flume-data

./kafka-console-producer.sh --broker-list 127.0.0.1:9092 --topic flume-data

创建conf/flume.conf:

```conf
agent.sources = kafkaSource
agent.channels = memoryChannel
agent.sinks = hdfsSink
 
 
# The channel can be defined as follows.
agent.sources.kafkaSource.channels = memoryChannel
agent.sources.kafkaSource.type=org.apache.flume.source.kafka.KafkaSource
agent.sources.kafkaSource.zookeeperConnect=127.0.0.1:2181
agent.sources.kafkaSource.topic=flume-data
#agent.sources.kafkaSource.groupId=flume
agent.sources.kafkaSource.kafka.consumer.timeout.ms=100
 
agent.channels.memoryChannel.type=memory
agent.channels.memoryChannel.capacity=1000
agent.channels.memoryChannel.transactionCapacity=100
 
 
# the sink of hdfs
agent.sinks.hdfsSink.type=hdfs
agent.sinks.hdfsSink.channel = memoryChannel
agent.sinks.hdfsSink.hdfs.path=hdfs://localhost:8020/user/wangwang/flume/dt=%Y%m%d
agent.sinks.hdfsSink.hdfs.filePrefix=%H
agent.sinks.hdfsSink.hdfs.fileSuffix=.log
agent.sinks.hdfsSink.hdfs.writeFormat=Text
agent.sinks.hdfsSink.hdfs.fileType=DataStream
```

bin/flume-ng agent --conf conf --conf-file conf/flume.conf --name agent -Dflume.root.logger=INFO,console

hadoop fs -ls /user/wangwang



```
hadoop fs -mkdir /user/wangwang/flume

CREATE EXTERNAL TABLE flume(id INT, amount INT,userId INT, createTime BIGINT)
PARTITIONED BY (dt INT)
ROW FORMAT SERDE 'org.apache.hive.hcatalog.data.JsonSerDe'
STORED AS TEXTFILE
LOCATION '/user/wangwang/flume';

{"id":4,"amount":20,"userId":333333,"createTime":1534208196000}

ALTER TABLE flume ADD PARTITION (dt=20180901) LOCATION '/user/wangwang/flume/dt=20180901';

```





hadoop fs -mkdir /user/wangwang/flume/dt=20180821

hadoop fs -put /Users/wangwang/Downloads/20180819.1534672473904.json /user/wangwang/flume/dt=20180821/

hdfs dfs -rmr /user/wangwang/flume/dt=20180901/20180819.1534682210073.json