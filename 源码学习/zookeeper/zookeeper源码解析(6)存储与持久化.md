## ZKDatabase

zookeeper中将抽象数据的处理抽象成一个类:ZKDatabase

在这个类中,zk使用dataTree来处理所有node的内存存储

使用FileTxnSnapLog来完成事务请求和快照的持久化

并且还记录着minCommittedLog,maxCommittedLog,commitLogCount等值

```java
public class ZKDatabase {
    
    private static final Logger LOG = LoggerFactory.getLogger(ZKDatabase.class);
    
    /**
     * make sure on a clear you take care of 
     * all these members.
     */
    protected DataTree dataTree;
    protected ConcurrentHashMap<Long, Integer> sessionsWithTimeouts;
    protected FileTxnSnapLog snapLog;
    protected long minCommittedLog, maxCommittedLog;
    public static final int commitLogCount = 500;
    protected static int commitLogBuffer = 700;
    protected LinkedList<Proposal> committedLog = new LinkedList<Proposal>();
    protected ReentrantReadWriteLock logLock = new ReentrantReadWriteLock();
    volatile private boolean initialized = false;
```

## DataTree

每个zk节点会在内存中维护一个DataTree的实例,这个对象保存了当前用户创建的所有节点信息

先看属性:

```java

public class DataTree {
    private static final Logger LOG = LoggerFactory.getLogger(DataTree.class);

	//将所有的nodes放到一个HashMap中
    private final ConcurrentHashMap<String, DataNode> nodes =
        new ConcurrentHashMap<String, DataNode>();

    //datawatch的管理者
    private final WatchManager dataWatches = new WatchManager();

    //childwatch的管理者
    private final WatchManager childWatches = new WatchManager();
    ...
	
    /**
     * This hashtable lists the paths of the ephemeral nodes of a session.
     临时节点被放在了一个HashMap,key是客户端和服务端连接的session的ID
     */
    private final Map<Long, HashSet<String>> ephemerals =
        new ConcurrentHashMap<Long, HashSet<String>>();

```

## DataTree增加节点

```java
    public String createNode(String path, byte data[], List<ACL> acl,
            long ephemeralOwner, int parentCVersion, long zxid, long time)
            throws KeeperException.NoNodeException,
            KeeperException.NodeExistsException {
        int lastSlash = path.lastIndexOf('/');
        String parentName = path.substring(0, lastSlash);
        String childName = path.substring(lastSlash + 1);
        //构造StatPersisted(描述一个Node信息的实体类)
        StatPersisted stat = new StatPersisted();
        stat.setCtime(time);
        stat.setMtime(time);
        stat.setCzxid(zxid);
        stat.setMzxid(zxid);
        stat.setPzxid(zxid);
        stat.setVersion(0);
        stat.setAversion(0);
        stat.setEphemeralOwner(ephemeralOwner);
        DataNode parent = nodes.get(parentName);
        if (parent == null) {
            throw new KeeperException.NoNodeException();
        }
        //对父节点的操作要加锁
        synchronized (parent) {
            Set<String> children = parent.getChildren();
            if (children.contains(childName)) {
                throw new KeeperException.NodeExistsException();
            }
            
            if (parentCVersion == -1) {
                parentCVersion = parent.stat.getCversion();
                parentCVersion++;
            }    
            parent.stat.setCversion(parentCVersion);
            parent.stat.setPzxid(zxid);
            Long longval = aclCache.convertAcls(acl);
            DataNode child = new DataNode(parent, data, longval, stat);
            parent.addChild(childName);
            //放入全局属性nodes(一个包容所有node的map,key是路径)
            nodes.put(path, child);
            if (ephemeralOwner != 0) {
                HashSet<String> list = ephemerals.get(ephemeralOwner);
                if (list == null) {
                    list = new HashSet<String>();
                    //处理临时节点
                    ephemerals.put(ephemeralOwner, list);
                }
                synchronized (list) {
                    list.add(path);
                }
            }
        }
        // now check if its one of the zookeeper node child
        if (parentName.startsWith(quotaZookeeper)) {
            // now check if its the limit node
            if (Quotas.limitNode.equals(childName)) {
                // this is the limit node
                // get the parent and add it to the trie
                pTrie.addPath(parentName.substring(quotaZookeeper.length()));
            }
            if (Quotas.statNode.equals(childName)) {
                updateQuotaForPath(parentName
                        .substring(quotaZookeeper.length()));
            }
        }
        // also check to update the quotas for this node
        String lastPrefix;
        if((lastPrefix = getMaxPrefixWithQuota(path)) != null) {
            // ok we have some match and need to update
            updateCount(lastPrefix, 1);
            updateBytes(lastPrefix, data == null ? 0 : data.length);
        }
        //触发新增节点事件对应的的watcher
        dataWatches.triggerWatch(path, Event.EventType.NodeCreated);
        //触发父节点的子节点变更事件对应的watcher
        childWatches.triggerWatch(parentName.equals("") ? "/" : parentName,
                Event.EventType.NodeChildrenChanged);
        return path;
    }
```

