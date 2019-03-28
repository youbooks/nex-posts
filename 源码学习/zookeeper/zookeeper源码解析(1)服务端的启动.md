


```java
public class QuorumPeer extends ZooKeeperThread implements QuorumStats.Provider {
...    

@Override
    public synchronized void start() {
        loadDataBase();
        cnxnFactory.start();        
        startLeaderElection();
        super.start();
    }
```

