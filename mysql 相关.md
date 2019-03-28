
##1.切入

看local代码的时候看到这样一条sql,优化了一下,仅供参考~

![](https://ws3.sinaimg.cn/large/006tNc79gy1g04rpddyy7j320w0ggdv2.jpg)

![](https://ws1.sinaimg.cn/large/006tNc79gy1g04rr3aoanj31if0u0n8p.jpg)

问题:
1.查询效率差:如explain所示,有很多子查询,还有临时表和filesort
2.可读性差:各种表的嵌套join
3.逻辑错误:这里 t1.deleted = 0 其实是不会生效的,仍然会查出 t1.deleted = 1的数据

优化点:
1.关联字段加索引
2.子查询效率很差,这里去除子查询直接left join 其他表, 过滤条件用and跟在join后面
(注意:当没必要使用left join的时候, 请使用join,因为使用join的话MySQL会我们选择驱动表,就不会出现大表驱动小表这种低效情况)

优化后:
```sql
        
alter table local_business_invite add index idx_business_id(`business_id`);
alter table local_business add index idx_deleted(`deleted`);
alter table local_deal_category add index idx_parent_id(`parent_id`);
alter table local_business_user_rel add index idx_bind_status(`bind_status`);

select t1.id, t1.name, t1.name_en, t5.submit_time as create_time, t1.city_id, t1.address, t1.phone, t2.category_id, t4.id as buss_invite_id, t4.last_invite_time, t4.invite_email
        , t5.business_user_id, t5.bind_status
         from local_business t1 
         left join local_business_category t2 on t1.id = t2.business_id 
         left join local_deal_category t3 on t2.category_id = t3.id and t3.parent_id = 0
         left join local_business_invite t4 on t1.id = t4.business_id and t4.role = "owner"
         left join local_business_user_rel t5 on t1.id = t5.business_id and t5.bind_status = 'binded'
         where t1.deleted = 0 
        group by t1.id;      

```

![](https://ws3.sinaimg.cn/large/006tNc79gy1g04rqdlkgcj31ng0oq10m.jpg)



## 2. left join 跟 and 和 where 的区别

(1):ON后面的筛选条件主要是针对的是关联表【而对于主表筛选条件不适用】--- 这也是为什么第一种写法中: t1.deleted = 0 其实是不会生效的 原因

(2):对于主表的筛选条件应放在where后面，不应该放在ON后面

(3):对于关联表我们要区分对待。如果是要条件查询后才连接应该把查询件放置于ON后。如果是想再连接完毕后才筛选就应把条件放置于where后面



##  3. explain命令得到的分析解析

一开始对"type"理解不够深入,以这个例子,深入理解每个type的含义

### 3.1 type

#### 3.1.1 ref

>  ref可以用于使用 **=、or <=>** 运算符进行比较的非唯一索引列 。

#### 3.1.2 eq_ref

> 唯一性索引扫描，对于每个索引键，表中只有一条记录与之匹配。常见于主键或唯一索引扫描

#### 3.1.3  const, system

> 单表中最多有一个匹配行，查询起来非常迅速，例如根据主键或唯一索引查询。system是const类型的特例，当查询的表只有一行的情况下， 使用system。`

### 3.2 sql执行过程对照分析

为什么explain得到的结果有5条数据?那是因为每查询一条记录都执行了5次操作:

- 第一条 : 

  ```sql
  - select * from local_business t1 where t1.deleted = 0 
  ```

  得到 一条数据 A:(没有截完,因为太长了...)

  ![](https://ws1.sinaimg.cn/large/006tNc79gy1g05080va01j31uu02saba.jpg)

- 第二条 : 

  ```sql
  select * from local_business_category t2 where t2.business_id = 9965
  ```

  得到:![](https://ws2.sinaimg.cn/large/006tNc79gy1g050ijsaxnj30ew02kmxc.jpg)

  正如explain命令所示: type=ref,  因为这个查询走的字段business_id拥有索引

- 第三条 :

  ```sql
  select * from local_deal_category t3 where t3.id = 22 and t3.parent_id = 0
  ```

  Idx_parent_id这个索引是唯一索引,所以类型是eq_ref

剩下的两条可以依次类推


##  4. in 和 exists

### 4.1 还是接着最开始的例子:

exists的写法:

```sql
select t1.id, t1.name, t1.name_en, t5.submit_time as create_time, t1.city_id, t1.address, t1.phone, t2.category_id, t4.id as buss_invite_id, t4.last_invite_time, t4.invite_email
        , t5.business_user_id, t5.bind_status
         from local_business t1 
         left join local_business_category t2 on t1.id = t2.business_id 
         left join local_deal_category t3 on t2.category_id = t3.id and t3.parent_id = 0
         left join local_business_invite t4 on t1.id = t4.business_id and t4.role = "owner"
         left join local_business_user_rel t5 on t1.id = t5.business_id and t5.bind_status = 'binded'
         where t1.deleted = 0 
         and exists (SELECT * FROM `local_business_dish_menu` t6 WHERE t1.id = t6.business_id and t6.status = 'active')
        group by t1.id;
```

in的写法:

```sql
select t1.id, t1.name, t1.name_en, t5.submit_time as create_time, t1.city_id, t1.address, t1.phone, t2.category_id, t4.id as buss_invite_id, t4.last_invite_time, t4.invite_email
        , t5.business_user_id, t5.bind_status
         from local_business t1 
         left join local_business_category t2 on t1.id = t2.business_id 
         left join local_deal_category t3 on t2.category_id = t3.id and t3.parent_id = 0
         left join local_business_invite t4 on t1.id = t4.business_id and t4.role = "owner"
         left join local_business_user_rel t5 on t1.id = t5.business_id and t5.bind_status = 'binded'
         where t1.deleted = 0 
         and t1.id in (SELECT business_id FROM `local_business_dish_menu` t6 WHERE t6.status = 'active')
        group by t1.id;
```

### 4.2 两者的区别

如果用伪代码来表示,in的查询过程类似于:

```java
$result = [];
$localBusinessDishMenu = "SELECT * FROM `local_business_dish_menu`";
$localBusiness = "SELECT * FROM `local_business`";
for($i = 0;$i < $localBusiness.length;$i++){
    for($j = 0;$j < $localBusinessDishMenu.length;$j++){
        // 此过程为内存操作，不涉及数据库查询。
        if($localBusinessDishMenu[$j].business_id == $localBusiness[$i].id){
            $result[] = $localBusiness[$i];
            break;
        }
    }
}
```

exist的查询过程类似于:

```java
$result = [];
$localBusiness = "SELECT * FROM `local_business`";
for($i=0;$i<$localBusiness.length;$i++){
    if(exists($localBusiness[$i].id)){// 执行SELECT * FROM `local_business_dish_menu` WHERE local_business.id = local_business_dish_menu.business_id
        $result[] = $users[$i];
    }
}
```

### 4.3一句话总结区别

exists:确不确定每次都用**sql查**

in:确不确定每次都**内存循环查**

### 4.4 何时使用exists 何时使用in

其实明白原理后,对两者的使用的选择可以有一个认识:

> 外层查询表小于子查询表，则用exists，外层查询表大于子查询表，则用in，如果外层和子查询表差不多，则爱用哪个用哪个。

为什么? 因为

- 外层大,内层小:sql查询次数不多,多的话放内存查询更好
- 外层小,内层大:如果用sql查(exists),则要查太多次,这时候直接用内存查更好



## 5. using temporary | filesort



## 6. 聚簇索引,非聚簇索引,辅助索引和覆盖索引

### 6.1 引子

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g05qskuhbij316i0u6wjn.jpg)

这段对话发生在今上午,一个同事提了一下覆盖索引,顿觉这块没有完全掌握,所以这里做一个学习记录

### 6.2 跳转

这篇文章:https://www.jianshu.com/p/fa8192853184讲了非常详细了,直接阅读就好





## 7 MVCC和gap锁

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g05qqd7i1sj310a0foq5s.jpg)

这是在一个网站上我的自问自答,当时其实很困惑如果每次都加锁来解决幻读,那么性能不会好, 后来才知道当时把**快照读**和**当前读** 搞混了



## 8.MySQL的数据类型
###8.1 ENUM



