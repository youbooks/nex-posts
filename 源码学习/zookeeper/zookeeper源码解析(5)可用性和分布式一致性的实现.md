## 事务性消息是什么





## 关于zk一致性的一个误区

最初看代码的时候,我很疑惑的是:类似于2PC的zab协议,为什么没有rollback机制?

换句话说,不管有没有达成quorum,zk都会进行写入,代码如下:

```java
public class SyncRequestProcessor extends ZooKeeperCriticalThread implements RequestProcessor {
    ...
    @Override
    public void run() {
        try {
	...
                if (si != null) {
                    // track the number of records written to the log
                    //在这里进行了写入
                    if (zks.getZKDatabase().append(si)) {

```

```java
public class FileTxnLog implements TxnLog {
...   
/**
     * append an entry to the transaction log
     * @param hdr the header of the transaction
     * @param txn the transaction part of the entry
     * returns true iff something appended, otw false 
     */
    public synchronized boolean append(TxnHeader hdr, Record txn)
        throws IOException
    {
        if (hdr == null) {
            return false;
        }
//hdr中的zxid比已经append的最新zxid小,那么不需要再append
        if (hdr.getZxid() <= lastZxidSeen) {
            LOG.warn("Current zxid " + hdr.getZxid()
                    + " is <= " + lastZxidSeen + " for "
                    + hdr.getType());
        } else {
            lastZxidSeen = hdr.getZxid();
        }

        if (logStream==null) {
           if(LOG.isInfoEnabled()){
                LOG.info("Creating new log file: " + Util.makeLogName(hdr.getZxid()));
           }
//调用Util.makeLogName()获取日志文件的名字
            logFileWrite = new File(logDir, Util.makeLogName(hdr.getZxid()));
            //获取FileOutputStream
            fos = new FileOutputStream(logFileWrite);
            //获取BufferedOutputStream
            logStream=new BufferedOutputStream(fos);
            //获取BinaryOutputArchive, zk使用jute来实现文件的写入和读取,序列和反序列化
            oa = BinaryOutputArchive.getArchive(logStream);
            FileHeader fhdr = new FileHeader(TXNLOG_MAGIC,VERSION, dbId);
            fhdr.serialize(oa, "fileheader");
            // Make sure that the magic number is written before padding.
            logStream.flush();
            currentSize = fos.getChannel().position();
            streamsToFlush.add(fos);
        }
        currentSize = padFile(fos.getChannel());
        byte[] buf = Util.marshallTxnEntry(hdr, txn);
        if (buf == null || buf.length == 0) {
            throw new IOException("Faulty serialization for header " +
                    "and txn");
        }
        Checksum crc = makeChecksumAlgorithm();
        crc.update(buf, 0, buf.length);
        oa.writeLong(crc.getValue(), "txnEntryCRC");
    //使用DataOutput把buf写入到文件
        Util.writeTxnBytes(oa, buf);

        return true;
    }

```

我困惑的是如果只有少于一半的follower发送了ack,那么其实这个事务是不应该提交的,但是这里已经刷盘(flush)了

于是我去寻找有没有地方可以将这里写入的错误日志给删除的方法,看起来有一个方法比较像:

```java
public class FileTxnLog implements TxnLog {
...    

/**    截短当前的事务日志文件
     * truncate the current transaction logs
     * @param zxid the zxid to truncate the logs to
     * @return true if successful false if not
     */
    public boolean truncate(long zxid) throws IOException {
        FileTxnIterator itr = null;
        try {
            itr = new FileTxnIterator(this.logDir, zxid);
            PositionInputStream input = itr.inputStream;
            if(input == null) {
                throw new IOException("No log files found to truncate! This could " +
                        "happen if you still have snapshots from an old setup or " +
                        "log files were deleted accidentally or dataLogDir was changed in zoo.cfg.");
            }
            long pos = input.getPosition();
            // now, truncate at the current position
            RandomAccessFile raf = new RandomAccessFile(itr.logFile, "rw");
            raf.setLength(pos);
            raf.close();
            while (itr.goToNextLog()) {
                if (!itr.logFile.delete()) {
                    LOG.warn("Unable to truncate {}", itr.logFile);
                }
            }
        } finally {
            close(itr);
        }
        return true;
    }
```

但是我发现这个方法仅仅会在Leaner.syncWithLeader()的时候被调用,即只会在follower初始化和leader同步的时候进行调用,所以truncate并不是我的问题的答案

我反复地研究TxnLog这个类

```java
public interface TxnLog {
   
    void rollLog() throws IOException;
    
    boolean append(TxnHeader hdr, Record r) throws IOException;

    TxnIterator read(long zxid) throws IOException;
    
    long getLastLoggedZxid() throws IOException;

    boolean truncate(long zxid) throws IOException;
    
    long getDbId() throws IOException;
    
    void commit() throws IOException;

    void close() throws IOException;

    public interface TxnIterator {
        TxnHeader getHeader();
        
        Record getTxn();
     
        boolean next() throws IOException;

        void close() throws IOException;
    }
}
```

规范的写法会像上面一样,把需要暴露给别的地方使用的方法写到接口中,通过探寻每个方法的被调用的地方,我渐渐发现,

TxnLog的在持久化中的重要性没有我想象的大,也就是说,txnLog的写入是非事务性的,允许不同节点的事务日志文件存在不一致,即便是没有最终被commit的request,也会被记录在TxnLog中

## commit过程发生了什么

在leader这个类里面,有一个加了锁的方法来处理learner反馈的ack(之所以加锁,是因为要利用单线程同步来实现一致性)

```java
public class Leader {
...
        synchronized public void processAck(long sid, long zxid, SocketAddress followerAddr) {
		...
        //根据zxid获取提议
        Proposal p = outstandingProposals.get(zxid);
        if (p == null) {
            LOG.warn("Trying to commit future proposal: zxid 0x{} from {}",
                    Long.toHexString(zxid), followerAddr);
            return;
        }
        //对ackSet进行累加
        p.ackSet.add(sid);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Count for zxid: 0x{} is {}",
                    Long.toHexString(zxid), p.ackSet.size());
        }
    	//执行quorum合法性检测(ackset中的数量大于总数一半,即为合法),如果合法,那么进行下面的操作
        if (self.getQuorumVerifier().containsQuorum(p.ackSet)){             
            if (zxid != lastCommitted+1) {
                LOG.warn("Commiting zxid 0x{} from {} not first!",
                        Long.toHexString(zxid), followerAddr);
                LOG.warn("First is 0x{}", Long.toHexString(lastCommitted + 1));
            }
            outstandingProposals.remove(zxid);
            if (p.request != null) {
                toBeApplied.add(p);
            }

            if (p.request == null) {
                LOG.warn("Going to commmit null request for proposal: {}", p);
            }
            //followe进行commit
            commit(zxid);
            //observer进行通知(因为observer没有事务性)
            inform(p);
            zk.commitProcessor.commit(p.request);
            if(pendingSyncs.containsKey(zxid)){
                for(LearnerSyncRequest r: pendingSyncs.remove(zxid)) {
                    sendSync(r);
                }
            }
        }
    }
```

在follower中,会对通过quorum验证的消息进行提交

```java
    /**
     * the process txn on the data
     * @param hdr the txnheader for the txn
     * @param txn the transaction that needs to be processed
     * @return the result of processing the transaction on this
     * datatree/zkdatabase
     */
    public ProcessTxnResult processTxn(TxnHeader hdr, Record txn) {
        return dataTree.processTxn(hdr, txn);
    }
```

这个方法中只有对dataTree的操作,并不涉及到txnLog,正如我们上文中说到的,txnLog的写入是非事务性的