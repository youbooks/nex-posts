## CommitLog

用于存储消息的抽象封装, 内部采用[MapedFileQueue](https://www.jianshu.com/p/f9052d9b637c)实现了消息文件队列功能.

### 关键字段

- HashMap topicQueueTable: 用于记录某个topic在某个queueId共写入了多少个消息, put一个消息加1.

### 关键方法

- putMessage: 存储消息.
- getMessage: 读取消息

(1) putMessage
 存储消息主要分3步: 查找文件(getLastMapedFile), 写入数据(DefaultAppendMessageCallback), 刷盘(FlushRealTimeService). 最终产生实际存储消息的队列文件如下:
 ${storePathRootDir}/commitlog/消息队列文件. (消息队列文件名规则如MappedFileQueue).

(2)getMessage(final long offset, final int size)
 offset: 绝对偏移量, 可以用其调用findMappedFileByOffset查询MappedFile.
 size: 欲查询的数据大小.

`MESSAGE` 在 `CommitLog` 存储结构：

| 第几位 | 字段                          | 说明                                      | 数据类型      | 字节数               |
| ------ | ----------------------------- | ----------------------------------------- | ------------- | -------------------- |
| 1      | MsgLen                        | 消息总长度                                | Int           | 4                    |
| 2      | MagicCode                     | MESSAGE_MAGIC_CODE                        | Int           | 4                    |
| 3      | BodyCRC                       | 消息内容CRC                               | Int           | 4                    |
| 4      | QueueId                       | 消息队列编号                              | Int           | 4                    |
| 5      | Flag                          | flag                                      | Int           | 4                    |
| 6      | QueueOffset                   | 消息队列位置                              | Long          | 8                    |
| 7      | PhysicalOffset                | 物理位置。在 `CommitLog` 的顺序存储位置。 | Long          | 8                    |
| 8      | SysFlag                       | MessageSysFlag                            | Int           | 4                    |
| 9      | BornTimestamp                 | 生成消息时间戳                            | Long          | 8                    |
| 10     | BornHost                      | 生效消息的地址+端口                       | Long          | 8                    |
| 11     | StoreTimestamp                | 存储消息时间戳                            | Long          | 8                    |
| 12     | StoreHost                     | 存储消息的地址+端口                       | Long          | 8                    |
| 13     | ReconsumeTimes                | 重新消费消息次数                          | Int           | 4                    |
| 14     | PreparedTransationOffset      |                                           | Long          | 8                    |
| 15     | BodyLength + Body             | 内容长度 + 内容                           | Int + Bytes   | 4 + bodyLength       |
| 16     | TopicLength + Topic           | Topic长度 + Topic                         | Byte + Bytes  | 1 + topicLength      |
| 17     | PropertiesLength + Properties | 拓展字段长度 + 拓展字段                   | Short + Bytes | 2 + PropertiesLength |

`BLANK` 在 `CommitLog` 存储结构：

| 第几位 | 字段      | 说明             | 数据类型 | 字节数 |
| ------ | --------- | ---------------- | -------- | ------ |
| 1      | maxBlank  | 空白长度         | Int      | 4      |
| 2      | MagicCode | BLANK_MAGIC_CODE | Int      | 4      |

![](https://ws2.sinaimg.cn/large/006tNbRwgy1fx9y3nlsf3j30xv0m474x.jpg)



## ConsumeQueue

消费队列的实现, 该消费队列主要存储了消息在CommitLog的位置, 与CommitLog类似, 内部采用MappedFileQueue实现了消息位置文件队列功能.
 一个topic和一个queueId对应一个ConsumeQueue.
 默认queue存储30W条消息, 每个消息大小为20个字节, 详细如下:
 offset(long 8字节) + size(int 4字节) + tagsCode(long 8字节)

### 

![](https://ws3.sinaimg.cn/large/006tNbRwgy1fx9y4s9jupj30l50c6q2u.jpg)

### 关键方法

- putMessagePositionInfo: 消息位置的存储
- getIndexBuffer: 该方法返回从offset之后的字节映射
   (1)putMessagePositionInfo(final long offset, final int size, final long tagsCode,
   final long cqOffset)
   offset: 消息在commitLog中的起始位置
   size: 消息长度
   tagsCode: 消息tag的hash code
   cqOffset: 该消息在topic对应的queue中的下标
   该方法主要实现了消息位置的存储, 并产生消息文件:
   storePathRootDir/consumequeue/{topic}/${queueId}/消息位置队列文件
   消息数(30W)*消息位置固定大小(20字节)=6000000字节
   故每6000000字节一个文件, 文件名依次递增, 前缀不够20位补0, 类似如下:
   00000000000000000000
   00000000000006000000
   00000000000012000000

(2)getIndexBuffer(final long startIndex)
 该方法源代码如下:

```
public SelectMappedBufferResult getIndexBuffer(final long startIndex) {
    int mappedFileSize = this.mappedFileSize;
    long offset = startIndex * CQ_STORE_UNIT_SIZE;
    if (offset >= this.getMinLogicOffset()) {
        MappedFile mappedFile = this.mappedFileQueue.findMappedFileByOffset(offset);
        if (mappedFile != null) {
            SelectMappedBufferResult result = mappedFile.selectMappedBuffer((int) (offset % mappedFileSize));
            return result;
        }
    }
    return null;
}
```

startIndex代表了起始偏移量索引.
 该方法先根据startIndex找到对应的MappedFile, 再在该MappedFile中找到对应的字节映射.



## MappedFile

MappedFile是"内存文件映射", 是对[MappedByteBuffer](https://www.jianshu.com/p/f9052d9b637c)的封装, 具有创建文件（使用非堆区内存）, 写入, 提交, 读取, 释放, 关闭等功能, RocketMQ使用该类实现数据从内存到磁盘的持久化.

刷写的实现逻辑就是调用FileChannel或MappedByteBuffer的force方法。



### 关键字段

- fileChannel: 该类对应的文件通道.
- mappedByteBuffer: 文件在内存中的映射. 如前文所述RocketMQ使用内存映射的方式来操作文件, 这种方式要比流的方式快很多.
- fileSize: 文件尺寸
- wrotePosition: 当前写到哪一个位置.
- committedPosition: 已经提交(已经持久化到磁盘)的位置.
- flushedPosition: 已经提交(已经持久化到磁盘)的位置.
- writeBuffer: 内存字节缓冲区, RocketMQ提供两种数据落盘的方式: 一种是直接将数据写到映射文件字节缓冲区(mappedByteBuffer), 映射文件字节缓冲区(mappedByteBuffer)flush; 另一种是先写到writeBuffer, 再从内存字节缓冲区(write buffer)提交(commit)到文件通道(fileChannel), 然后文件通道(fileChannel)flush.
- fileFromOffset: fileFromOffset: 映射的起始偏移量, 拿commitlog文件来举例, 下面有很多个文件夹(假设为1KB, 默认是1G大小), 第一个文件名为00000000000000000000, 第二个文件名为00000000000000001024, 那么第一个文件的fileFromOffset就是0, 第二个文件的fileFromOffset就是1024

### 关键方法

- appendMessage: 插入消息到MappedFile, 并返回插入结果.
- selectMappedBuffer: 返回指定位置的内存映射, 用于读取数据.
   (1) appendMessage
   源代码如下:

```
public AppendMessageResult appendMessage(final MessageExtBrokerInner msg, final AppendMessageCallback cb) {
    assert msg != null;
    assert cb != null;

    int currentPos = this.wrotePosition.get();  //获取当前写的位置

    if (currentPos < this.fileSize) {   //currentPos小于文件尺寸才能写入
        //获取获取需要写入的字节缓冲区, 之所以会有writeBuffer != null的判断与使用的刷盘服务有关.
        ByteBuffer byteBuffer = writeBuffer != null ? writeBuffer.slice() : this.mappedByteBuffer.slice();
        byteBuffer.position(currentPos);    //设置写入的postion
        AppendMessageResult result =
            cb.doAppend(this.getFileFromOffset(), byteBuffer, this.fileSize - currentPos, msg);  //执行写入
        this.wrotePosition.addAndGet(result.getWroteBytes()); //更新wrotePosition
        this.storeTimestamp = result.getStoreTimestamp();
        return result;
    }
    //返回错误信息
    log.error("MappedFile.appendMessage return null, wrotePosition: " + currentPos + " fileSize: "
        + this.fileSize);
    return new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR);
}
```

可以看到MappedFile调用AppendMessageCallback来执行msg到字节缓冲区的写入.事实上整个RocketMQ只有一个类实现了AppendMessageCallback接口, 就是DefaultAppendMessageCallback. doAppend方法的具体实现与消息格式有关, 并且不属于MappedFile的范畴, 后文再分析.
 (2) selectMappedBuffer
 源代码如下:

```
//返回从pos到 pos + size的内存映射
public SelectMappedBufferResult selectMappedBuffer(int pos, int size) {
    int readPosition = getReadPosition();   //获取当前有效数据的最大位置
    if ((pos + size) <= readPosition) {    //内存映射的最大位置必须小于readPosition

        if (this.hold()) {    //引用计数
            ByteBuffer byteBuffer = this.mappedByteBuffer.slice();  // 复制一个byteBuffer(与原byteBuffer共享数据, 只是指针位置独立)
            byteBuffer.position(pos);    //设置position
            //获取目标数据
            ByteBuffer byteBufferNew = byteBuffer.slice();
            byteBufferNew.limit(size);
            return new SelectMappedBufferResult(this.fileFromOffset + pos, byteBufferNew, size, this);
        } else {
            log.warn("matched, but hold failed, request pos: " + pos + ", fileFromOffset: "
                + this.fileFromOffset);
        }
    } else {
        log.warn("selectMappedBuffer request pos invalid, request pos: " + pos + ", size: " + size
            + ", fileFromOffset: " + this.fileFromOffset);
    }

    return null;
}
```

## 2. MappedFileQueue

顾名思义, 该类代表了[MappedFile](https://www.jianshu.com/p/f9052d9b637c)组成的队列(由大小相同的多个文件构成). 无论是CommitLog(消息主体以及元数据), 还是ConsumeQueue(逻辑队列), 底层使用的组件都是MappedFileQueue.

### 关键字段

- storePath: 文件队列的存储路径
- mappedFiles: 存储MappedFile的map
- mappedFileSize: MappedFile的尺寸
- flushedWhere: 已经刷到磁盘的位置
- committedWhere: 已经提交的位置

### 关键方法

- getLastMappedFile: 获取队列中最后一个MappedFile对象
- findMappedFileByOffset: 根据offset/filesize计算该offset所在那个文件中

(1) getLastMappedFile
 源代码如下:

```
public MappedFile getLastMappedFile(final long startOffset, boolean needCreate) {
    long createOffset = -1;
    //获取当前Queue中最后一个MappedFile
    MappedFile mappedFileLast = getLastMappedFile();

    //一个文件都不存在时, 计算起始文件的offset
    if (mappedFileLast == null) {
        createOffset = startOffset - (startOffset % this.mappedFileSize);
    }
    //计算需要新创建的文件的offset
    if (mappedFileLast != null && mappedFileLast.isFull()) {
        createOffset = mappedFileLast.getFileFromOffset() + this.mappedFileSize;
    }

    //创建新的MappedFile
    if (createOffset != -1 && needCreate) {
        //计算文件名
        String nextFilePath = this.storePath + File.separator + UtilAll.offset2FileName(createOffset);
        String nextNextFilePath = this.storePath + File.separator
            + UtilAll.offset2FileName(createOffset + this.mappedFileSize);
        MappedFile mappedFile = null;

        if (this.allocateMappedFileService != null) {
            //使用AllocateMappedFileService创建文件主要是更加安全一些, 会将一些并行的操作穿行化
            mappedFile = this.allocateMappedFileService.putRequestAndReturnMappedFile(nextFilePath,
                nextNextFilePath, this.mappedFileSize);
        } else {
            try {
                mappedFile = new MappedFile(nextFilePath, this.mappedFileSize);
            } catch (IOException e) {
                log.error("create mappedFile exception", e);
            }
        }

        //将新创建的文件添加到队列中
        if (mappedFile != null) {
            if (this.mappedFiles.isEmpty()) {
                mappedFile.setFirstCreateInQueue(true);
            }
            this.mappedFiles.add(mappedFile);
        }

        return mappedFile;
    }

    return mappedFileLast;
}
```

从源码中可见, 只有当文件写满或者找不到文件时, 才会创建新的文件.
 (2) findMappedFileByOffset
 主要是根据offset寻找对应的MappedFile, 具体源代码不再贴出.
 为了理解findMapedFileByOffset, 我们假设每个文件的大小是1024K, 参考以下图示:

![](https://ws3.sinaimg.cn/large/006tNbRwgy1fx9yc4xpn1j30el07vdft.jpg)

如果现在想查找3021在那个文件中, 可以按如下计算:
 (3021 - 0)/1024=2 即可知其在队列下标为2的MappedFile中
 释义如下: (offset-第一个文件的fileFromeOffset)/mappedFileSize