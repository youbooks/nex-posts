## 问题

最近工作中出现了一个问题: 为什么我一个方法A加了@transactional注解,但是方法内调用别的方法B,

B却没有和A在同一个事务中,代码类似如下:

方法A:

```java
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createComplimentaryOrder(Map<Integer, RaffleUserEntity> raffleUserIdMap, Map<String, RaffleUserEntity> raffleOpenIdMap......) {
        .......
        mapper.insertByBatch(orderList);//插入订单
        orderGoodsSearch.indexAll(orderGoodsList);//进行ES索引
        .......
    }
```

方法B:

```java
    @Override
    public boolean indexAll(Collection<OrderGoodsEntity> records){
         threadPool.execute(() -> {
          ......    
          OrderEntity order = orderMapper.selectByPrimaryKey(order.getOrderId());//查询A方法传入的order,查出来的order竟然是Null!!
          ......
               });
    }
```

如果A跟B都在一个事务里面,那么显然order不会为空,相反,如果A和B在不同的事务,因为A还未真正写入,所以B肯定查不到

所以当时比较困惑我,经过阅读源码,我找到了问题点原因所在,记录在此,希望对读者有帮助

## spring中@transactional注解的原理

要解决上面的问题,不搞懂@transactional注解的原理肯定是不行了,其实也很简单,无外乎是一个aop(毕竟是spring的一大特性,用在事务上真的很合适)

首先,如果没有任何其他的框架和库,我们要使用事务会这样写(伪代码):

```java
begin;  # 开始事务
insert into mall_order value(5.....);
insert into mall_order value(6....);
insert into mall_order value(7....);
commit; # 提交事务
```

每次使用事务,都要这样写,其实做了很多重复工作,我们其实也并不关心事务怎么打开,提交和回滚,所以如果有一个人能帮助我们来操心这个,我们就只需要关心业务逻辑了,spring利用代理做到了这一点



![](https://ws4.sinaimg.cn/large/006tNc79gy1g02te25qxij31w00nc46t.jpg)

![](https://ws3.sinaimg.cn/large/006tNc79gy1g02tja8s3dj314g0u0atf.jpg)


接下来,重点关注TransactionAspectSupport.createTransactionIfNecessary()这个方法,精简下来就是如下

```java
TransactionStatus status = platformTransactionManager.getTransaction(transactionAttribute);
```

即使用事务管理器,根据事务属性,获取事务状态(TransactionStatus)

我们一直强调数据结构在程序中的重要性,在这里,搞定TransactionStatus看起来是必须要做的事

其实,TransactionStatus顾名思义,用来描述事务的状态,是一个非常重要的类,先看属性(一下合并了AbstractTransactionStatus和DefaultTransactionStatus中的属性)

```java
	private boolean rollbackOnly = false;//只允许回滚

	private boolean completed = false;//事务是否完成

	@Nullable
	private Object savepoint;//保存点

	@Nullable
	private final Object transaction;//归属的事务

	private final boolean newTransaction;//是否是新的事务

	private final boolean newSynchronization;//是否是新的同步器

	private final boolean readOnly;//是否只读

	private final boolean debug;

	@Nullable
	private final Object suspendedResources;//被暂停的事务
```

其中,savepoint和suspendedResources比较值得注意,因为前者实现了PROPAGATION_NESTED这个属性的回滚到某个点的功能,后者使得PROPAGATION_REQUIRES_NEW可以让两个嵌套事务互不影响,后文会一一详析

继续阅读上文platformTransactionManager.getTransaction()的实现,主要有三步:

![](https://ws4.sinaimg.cn/large/006tNc79gy1g02u7ie9jwj31a90u01dl.jpg)

#### 第一步,获取新的事务对象,代表当前方法对应的事务

![](https://ws4.sinaimg.cn/large/006tNc79gy1g02u1t0l1fj31rw0aw78j.jpg)

为什么要说是"当前线程对应的",因为TransactionSynchronizationManager使用ThreadLocal实现了资源的线程安全,每个线程都管理它自己对应的资源(如connection)
![](https://ws2.sinaimg.cn/large/006tNc79gy1g02u4zl0jgj31bw07wwgq.jpg)

![](https://ws1.sinaimg.cn/large/006tNc79gy1g02u4ql0agj31fk0kg426.jpg)

#### 第二步,处理已存在的事务

如果已经有已存在的事务,那么对于不同的传播属性,会有不同的处理(都在AbstractPlatformTransactionManager.handleExistingTransaction()方法,以下做了截取)

##### PROPAGATION_REQUIRES_NEW

![](https://ws4.sinaimg.cn/large/006tNc79gy1g02udr2c1aj31zk0m8jzt.jpg)

- 1 先挂起之前存在的事务,这里举个例子,A和B方法都被@Transactional注解修饰,B在A中被调用,当B方法被代理的时候,A对应的事务对象已经存在了,这时候事务A就要被挂起.但是只说一句被挂起太抽象,其实前面埋了伏笔,这里返回的suspendedResources会被当做newTransactionStatus的参数,那么新new的属于B的TransactionStatus中保留了A事务,当B事务执行完毕后,不论结果怎样,都会继续执行suspendedResources(即A事务)

  ![](https://ws1.sinaimg.cn/large/006tNc79gy1g02uuol0qtj31m20u0wtq.jpg)

- 2 生成新的TransactionStatus(注意传入了suspendedResources)
- 3 开启事务(doBegin方法会在后面解析,但其实核心的地方是:获取新的connection(因为suspend方法将当前线程绑定的connectionHolder置为了空))

如果当前事务执行完毕,无论失败与否,都会调用如下方法:

![](https://ws4.sinaimg.cn/large/006tNc79gy1g03i603uqcj31mq0hugr3.jpg)

所以,到这里我们也就明白了为什么PROPAGATION_REQUIRES_NEW这个传播属性是完全和外层事务隔离的,即内部事务的成败不会影响外部事务的成败

##### PROPAGATION_NESTED

![](https://ws1.sinaimg.cn/large/006tNc79gy1g02un96rc4j31wf0u04a2.jpg)

- 1 创建新的transactionStatus
- 2 创建保持点(savepoint)

else后面的代码都不用看了,因为是处理的JTA的东西,我们只用关心jdbc有关的东西

如果事务执行失败,那么会回滚到当前保持点,见代码如下:

![](https://ws1.sinaimg.cn/large/006tNc79gy1g03i3x0eeij31ja0loaec.jpg)



#### 第三步,没有已存在的事务,需要另起新事务

```java
else if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRED ||
				definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW ||
				definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NESTED) {
    .......
    doBegin(transaction, definition);
    ......  
}
```

这段代码告诉我们,PROPAGATION_REQUIRED,PROPAGATION_REQUIRES_NEW,PROPAGATION_NESTED这三个传播属性都会另起新事务然后执行

我们重点关注doBegin

![](https://ws1.sinaimg.cn/large/006tNc79gy1g03if293s1j30xt0u01ky.jpg)

至此,spring对事务的管理我们分析完了,画了一个流程图做一个小结:

![](https://ws2.sinaimg.cn/large/006tNc79gy1g03hmrmui0j30y80u049e.jpg)









## mybatis源码解析

每次我去研究一个开源项目的源码,我都会思考两个问题:

- 这个项目解决什么问题
- 如果是我来写,我会怎么实现

mybatis的回答:

- 方便了java用户使用sql操作数据库,如果没有mybatis,我们每次都要这样操作:

  ```java
  Connection conn=getConnection();
  sql="update customers set name='Tom' where id =2";
  statement = conn.createStatement();
  statement.execute(sql);
  ```

  试下一下,每次都要这样写,做了太多重复的工作

- 大体思路应该和mybatis差不多: 使用aop代理执行用户写的sql,用户只需要书写sql,执行和获取结果的过程由aop进行代理. 如果说的更详细点,那么过程应该是:

  ```
  1.项目启动时,扫描所以mapper的java接口和xml文件,为每个mapper生成代理类并保存到内存(用一个map保存,key可以设置为java类的签名),同时为每个代理类里的方法生成代理方法
  2.用户调用mapper的java接口,传入parameter的值
  3.根据方法入参构造最终的sql(注意,为什么要说最终的,因为第一步已经预处理了sql,生成了rootSql,包含了例如if,foreach等标签,这些标签在第一步做了保持,在第三步拿到参数后会做解析)
  4.jdbc执行statement.execute(sql);获取resultset,然后根据用户配置了resultType生成返回实体对象
  ```

mybatis的工作原理,和我上面的构想几乎完全吻合,下面我们以一条普通sql的被执行过程,来捋清整个过程,见识mybatis是怎样工作的( 这里插一句, 其实工作逻辑上面已经列出了,所以看源码,我主要就是看它是怎样设计数据结构的,因为我一直坚持  程序=数据结构+算法  的公式,上面的逻辑可以大致理解为"算法",所以我们的关注点就落在了数据结构上)

### 1.一条普通得不能再普通的sql(当然也很典型)

```sql
insert into mall_pay_order(total_amount,state,user_id)  values (100,'1',693650)
```

### 2.mybatis对这条sql的转化

xml文件里:

```xml
<insert id="insertSelective" parameterType="com.xxx.pojo.entity.PayOrderEntity" useGeneratedKeys="true" keyProperty="id">
    insert into mall_pay_order
    <trim prefix="(" suffix=")" suffixOverrides=",">
      <if test="totalAmount != null">
        total_amount,
      </if>
      <if test="state != null">
        state,
      </if>
      <if test="userId != null">
        user_id,
      </if>
    </trim>
    <trim prefix="values (" suffix=")" suffixOverrides=",">
      <if test="totalAmount != null">
        #{totalAmount,jdbcType=INTEGER},
      </if>
      <if test="state != null">
        #{state,jdbcType=VARCHAR},
      </if>
      <if test="userId != null">
        #{userId,jdbcType=INTEGER},
       </if>
    </trim>
  </insert>
```
java interface文件:

```java
public interface PayOrderEntityMapper {
        int insertSelective(PayOrderEntity record);
}
```

### 3.mybatis对这个mapper生成代理类

这个过程发生在项目启动时,mybatis扫描所有的mapper xml文件,对他们进行解析,生成 **MapperProxy**,**MapperMethod**和**MappedStatement**这三个主要的类,他们的关系是:

mapperInterface--->MapperProxy--->MapperMethod/MappedStatement

其中,MappedStatement是对MapperMethod的补充,保存了对应方法的一些重要信息

![](https://ws2.sinaimg.cn/large/006tNc79gy1fztbnrbipfj31oy0ro7bw.jpg)

MapperProxy采用工厂模式生成代理类

![](https://ws1.sinaimg.cn/large/006tNc79gy1fztbq2t5ubj31v40oajzo.jpg)

MapperProxy和mapperMethod的"有机结合",发生在如下的地方:

![](https://ws3.sinaimg.cn/large/006tNc79gy1fztbtgqq3aj31h10u048o.jpg)

### 4.执行代理

之前一直对sqlSession有所不解,不明白为什么会叫这个名字.

其实顾名思义,它代表一种"会话",一个"窗口",官方定义如下:

![](https://ws1.sinaimg.cn/large/006tNc79gy1fztbwrshitj31mq07ywml.jpg)

而MapperMethod的真正执行过程,正是它发挥作用的地方:

![](https://ws4.sinaimg.cn/large/006tNc79gy1fztbxxh8d5j31fw0p6dms.jpg)

我们进入sqlSession内部,以最开始的insert为例,其实会转为update,然后使用执行器executor来完成工作

![](https://ws4.sinaimg.cn/large/006tNc79gy1fztc1b8345j31is0d0wie.jpg)

### 5.mybatis中的executor

![](https://ws2.sinaimg.cn/large/006tNc79gy1fztc45a60mj30vk0e0tdm.jpg)

Executor是真正执行sql的一层,而之前的sqlSession是对它的一种抽象,属于一种对外的编程接口

可以看到主要四个之类:

CachingExecutor:可以缓存执行结果

SimpleExecutor:默认的执行器

ReuseExecutor:可以重用的执行器

BatchExecutor:可以执行批量操作的执行器

我们继续走下去,进入SimpleExecutor的update方法:

![](https://ws3.sinaimg.cn/large/006tNc79gy1fztc9c83h8j31xc0ikwl0.jpg)

### 6. sql解析

分为两步:1.预解析(发生在生成mappedStatement的过程) 2.最终解析

其实最终解析之所以要放在后面,是因为需要根据传入的参数来动态地拼接sql,例如<if>标签就是这时候被解析,在代码里,发生在mappedStatement调用getBoundSql()里面

![](https://ws3.sinaimg.cn/large/006tNc79gy1fztclhe5ybj31e60butcm.jpg)

得到的BoundSql是我们想要的最终sql

![](https://ws2.sinaimg.cn/large/006tNc79gy1fztcofe0tfj31e60butcm.jpg)

其实我前面提过,我们学习一个知名项目的时候,怎么样才能学到精髓?我觉得还是要经常问自己一个问题,就是如果是自己来实现这个东西,会怎么做?再看别人的实现是否更好. 所以应用到sql解析这里,mybatis我觉得让我学到很多,它将sql进行切分,然后分类. 每一截都是一个sqlNode,类图如下:

![](https://ws2.sinaimg.cn/large/006tNc79gy1fzte22no2cj31ug0fe76l.jpg)

其实顾名思义,**IfSqlnode**,**ChooseSqlNode**这种代表需要处理逻辑,**staticTextSqlNode**是直接用字符串就可以不需要任何处理,**mixedSqlNode**则涵盖了多种sqlnode. 下面我们还是以最开始的例子来看,mybatis有什么"骚操作":

![](https://ws3.sinaimg.cn/large/006tNc79gy1fztdk8kvryj315w0u0tnc.jpg)

- "insert into mall_pay_order"对应StaticTextSqlNode

- "(total_amount,state,user_id) "对应TrimSqlNode,它是mixedSqlNode的子类

sqlNode接口,只有一个方法:

![](https://ws1.sinaimg.cn/large/006tNc79gy1fztfq80gmxj30r405274r.jpg)

apply这个单词,第一看到我自己会觉得不好理解,其实如果理解为"使用,应用"会比较好,因为它就代表将这个sqlnode"应用"到最终的sqlContent上.

分别来看StaticTextSqlNode和mixedSqlNode

- ![](https://ws1.sinaimg.cn/large/006tNc79gy1fztftkmd98j31960be0v2.jpg)
- ![](https://ws3.sinaimg.cn/large/006tNc79gy1fztg2cecl4j314e0u0n9a.jpg)

得到的最终的boundSql如下:

![](https://ws4.sinaimg.cn/large/006tNc79gy1fztg6r4ezoj32cc0tmtn6.jpg)

### 7. sql执行

上面说明了,会通过handler来实现最终的调用

![](https://ws1.sinaimg.cn/large/006tNc79gy1fztgatl9krj31gg0s6n5q.jpg)



最后,用一张流程图做一个回顾:

![](https://ws3.sinaimg.cn/large/006tNc79gy1fzth5mg3e5j31ec0jywjd.jpg)



## spring和mybatis都是用的代理,不会有冲突吗?

看完spring和mybatis的实现原理,我的脑海里出现一个问题,既然控制事务的核心类是connection,那么如果要使spring和mybatis不产生冲突,那么他俩必须要使用同一个connection,而mybatis明明是自己维护的connection啊?这明显有问题

其实,mybatis还有一个项目叫:Spring integration for MyBatis 3(https://github.com/mybatis/spring)

顾名思义,就是mybatis和spring的结合,打开项目源码,我们发现一个类SpringManagedTransaction:

![](https://ws4.sinaimg.cn/large/006tNc79gy1fzt20ovfomj318q0u0n7h.jpg)

![](https://ws1.sinaimg.cn/large/006tNc79gy1fzt23k2o8oj31r20m0q9w.jpg)

![](https://ws2.sinaimg.cn/large/006tNc79gy1fzt25qsu48j31450u0dyr.jpg)

![](https://ws4.sinaimg.cn/large/006tNc79gy1fzt26talsgj31qg0a2q5v.jpg)

到这里,问题迎刃何解,因为上文已经提到,spring通过ThreadLocal实现了在同一个线程,mybatis和spring用到的connection保持一致!

## 回到最初的问题

其实答案已经很明显了,一个事务所使用的connection被spring使用ThreadLocal和线程进行了绑定

当我们在A中调用B的时候,B中使用了其他的线程来执行sql,那么肯定是不会被包括在当前事务中了



#### 参考:

mybatis-spring:https://github.com/mybatis/spring

mybatis-3:https://github.com/mybatis/mybatis-3

spring-framework:https://github.com/spring-projects/spring-framework



