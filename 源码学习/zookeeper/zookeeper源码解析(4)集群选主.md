## 一句话搞懂FastLeaderElection

快速选主算法其实非常简单,如果用一句话来概括的话,就是:所有节点互相通信,弱节点"臣服于"强节点,直到所有弱节点都被强节点"征服",而强弱的判别方法就是totalOrderPredicate,在下面会重点介绍

## FastLeaderElection的源码

所有的逻辑都蕴藏在这段loop里面

```java
    /**
     * Starts a new round of leader election. Whenever our QuorumPeer
     * changes its state to LOOKING, this method is invoked, and it
     * sends notifications to all other peers.
     */
    public Vote lookForLeader() throws InterruptedException {
      
        try {
            //接收vote的集合
            HashMap<Long, Vote> recvset = new HashMap<Long, Vote>();

            HashMap<Long, Vote> outofelection = new HashMap<Long, Vote>();

            int notTimeout = finalizeWait;

            synchronized(this){
                logicalclock.incrementAndGet();
                //初始化提议,获取到serverId,最大的记录到的zxid,以及当前的epoch
                updateProposal(getInitId(), getInitLastLoggedZxid(), getPeerEpoch());
            }

			//第一次发通知给所有peer
            sendNotifications();

            //当状态为LOOKING以及stop为false就一直循环,其实说白了就是一个"更新提议,再通知peer"的循环
            while ((self.getPeerState() == ServerState.LOOKING) &&
                    (!stop)){
				//从recvqueue拿取一个接收到的来自别的peer的通知
                Notification n = recvqueue.poll(notTimeout,
                        TimeUnit.MILLISECONDS);

                /*
                 如果没有更多的通知,那么就自己发,否则就处理这条通知
                 */
                if(n == null){
                    if(manager.haveDelivered()){
                        sendNotifications();
                    } else {
                        manager.connectAll();
                    }
                    int tmpTimeOut = notTimeout*2;
                    notTimeout = (tmpTimeOut < maxNotificationInterval?
                            tmpTimeOut : maxNotificationInterval);
                    LOG.info("Notification time out: " + notTimeout);
                }
                else if(self.getVotingView().containsKey(n.sid)) {
                    switch (n.state) {
                    case LOOKING:
                        // 如果n的electionEpoch大于当前的logicalclock,那么强制更新当前logicalclock为n的值,无论是否比当前节点"强",都要更新当前节点持有的serverId,zxid,peerEpoch
                        if (n.electionEpoch > logicalclock.get()) {
                            logicalclock.set(n.electionEpoch);
                            recvset.clear();
                            //检验n是否比当前节点的初始提议"强"
                            if(totalOrderPredicate(n.leader, n.zxid, n.peerEpoch,
                                    getInitId(), getInitLastLoggedZxid(), getPeerEpoch())) {
                                updateProposal(n.leader, n.zxid, n.peerEpoch);
                            } else {
                                updateProposal(getInitId(),
                                        getInitLastLoggedZxid(),
                                        getPeerEpoch());
                            }
                            sendNotifications();
                        } else if (n.electionEpoch < logicalclock.get()) {
                            if(LOG.isDebugEnabled()){
                                LOG.debug("Notification election epoch is smaller than logicalclock. n.electionEpoch = 0x"
                                        + Long.toHexString(n.electionEpoch)
                                        + ", logicalclock=0x" + Long.toHexString(logicalclock.get()));
                            }
                            break;
                        } 
                            //electionEpoch等于当前的logicalclock,那么检验n是否比当前节点的当前提议"强"
                            else if (totalOrderPredicate(n.leader, n.zxid, n.peerEpoch,
                                proposedLeader, proposedZxid, proposedEpoch)) {
                                //如果当前节点"输"给n,则n"征服"当前节点,体现在当前节点的serverId,zxid,peerEpoch被更新为n的值
                            updateProposal(n.leader, n.zxid, n.peerEpoch);
                                //使用更新后的值,发送给其他peer
                            sendNotifications();
                        }

                            //我们注意到,如果当前节点持有的serverId,zxid,peerEpoch不输给n,那么不会发通知给其他节点
                            
                      
                        recvset.put(n.sid, new Vote(n.leader, n.zxid, n.electionEpoch, n.peerEpoch));

                            //判断是否有足够的条件去宣布这轮选举的结束
                        if (termPredicate(recvset,
                                new Vote(proposedLeader, proposedZxid,
                                        logicalclock.get(), proposedEpoch))) {

                            //确认当前节点持有的proposed leader是否有变化
                            while((n = recvqueue.poll(finalizeWait,
                                    TimeUnit.MILLISECONDS)) != null){
                                if(totalOrderPredicate(n.leader, n.zxid, n.peerEpoch,
                                        proposedLeader, proposedZxid, proposedEpoch)){
                                    recvqueue.put(n);
                                    break;
                                }
                            }

                            /*
                             如果n为空,那么我们可以认为这轮选举可以结束了
                             */
                            if (n == null) {
                                //如果proposedLeader和当前节点自己的serverId相同,那么就是Leader,否则是folloer或者observer
                                self.setPeerState((proposedLeader == self.getId()) ?
                                        ServerState.LEADING: learningState());

                                Vote endVote = new Vote(proposedLeader,
                                                        proposedZxid,
                                                        logicalclock.get(),
                                                        proposedEpoch);
                                leaveInstance(endVote);
                                return endVote;
                            }
                        }
                        break;
                    case OBSERVING:
                        LOG.debug("Notification from observer: " + n.sid);
                        break;
                    case FOLLOWING:
                    case LEADING:
                        //如果收到的消息的状态为LEADING,那么就不需要再像LOOKING一样再去比较然后发新消息给peer,只需要比较确认自己的身份即可
                        if(n.electionEpoch == logicalclock.get()){
                            recvset.put(n.sid, new Vote(n.leader,
                                                          n.zxid,
                                                          n.electionEpoch,
                                                          n.peerEpoch));
                           
                            if(ooePredicate(recvset, outofelection, n)) {
                                //比较确认自己的身份
                                self.setPeerState((n.leader == self.getId()) ?
                                        ServerState.LEADING: learningState());

                                Vote endVote = new Vote(n.leader, 
                                        n.zxid, 
                                        n.electionEpoch, 
                                        n.peerEpoch);
                                leaveInstance(endVote);
                                return endVote;
                            }
                        }

                        /*
                         * Before joining an established ensemble, verify
                         * a majority is following the same leader.
                         */
                        outofelection.put(n.sid, new Vote(n.version,
                                                            n.leader,
                                                            n.zxid,
                                                            n.electionEpoch,
                                                            n.peerEpoch,
                                                            n.state));
           
                        if(ooePredicate(outofelection, outofelection, n)) {
                            synchronized(this){
                                logicalclock.set(n.electionEpoch);
                                self.setPeerState((n.leader == self.getId()) ?
                                        ServerState.LEADING: learningState());
                            }
                            Vote endVote = new Vote(n.leader,
                                                    n.zxid,
                                                    n.electionEpoch,
                                                    n.peerEpoch);
                            leaveInstance(endVote);
                            return endVote;
                        }
                        break;
                    default:
                        LOG.warn("Notification state unrecognized: {} (n.state), {} (n.sid)",
                                n.state, n.sid);
                        break;
                    }
                } else {
                    LOG.warn("Ignoring notification from non-cluster member " + n.sid);
                }
            }
            return null;
        } finally {
            try {
                if(self.jmxLeaderElectionBean != null){
                    MBeanRegistry.getInstance().unregister(
                            self.jmxLeaderElectionBean);
                }
            } catch (Exception e) {
                LOG.warn("Failed to unregister with JMX", e);
            }
            self.jmxLeaderElectionBean = null;
            LOG.debug("Number of connection processing threads: {}",
                    manager.getConnectionThreadCount());
        }
    }
```



## 如何判断孰强孰弱?

关键的两个方法:

```java
   /**
     检验新的pair(id,zxid,epoch)是否赢得了这次选举,或者说谁更强
     */
    protected boolean totalOrderPredicate(long newId, long newZxid, long newEpoch, long curId, long curZxid, long curEpoch) {
        LOG.debug("id: " + newId + ", proposed id: " + curId + ", zxid: 0x" +
                Long.toHexString(newZxid) + ", proposed zxid: 0x" + Long.toHexString(curZxid));
        if(self.getQuorumVerifier().getWeight(newId) == 0){
            return false;
        }
        

        
        /** 赢的条件是:
        1- 新epoch更大
        2- epoch一样,新zxid更大
        3- epoch,zxid一样,server id更大
        */
        return ((newEpoch > curEpoch) || 
                ((newEpoch == curEpoch) &&
                ((newZxid > curZxid) || ((newZxid == curZxid) && (newId > curId)))));
    }

```

```java
   /**
     * 根据给定的votes的集合,判断是否有足够的条件去宣布这轮选举的结束
     */
    protected boolean termPredicate(
            HashMap<Long, Vote> votes,
            Vote vote) {

        HashSet<Long> set = new HashSet<Long>();

        for (Map.Entry<Long,Vote> entry : votes.entrySet()) {
            if (vote.equals(entry.getValue())){
                set.add(entry.getKey());
            }
        }
		//返回true的条件是,set中item的数量大于总数的一半
        return self.getQuorumVerifier().containsQuorum(set);
    }
```

## 小结

如果我们确定一个竞争规则,例如跑的更快的人获胜成为leader,那么无论怎么比,跑得快的人终将成为leader

对于zk的FLE算法也是一样,先规定一个方法用来判断两两较量中谁赢,比赛结束后输家被迫变成赢家,然后又去和别人比,经过一轮又一轮的较量,所有输家最终都会变成同一个赢家