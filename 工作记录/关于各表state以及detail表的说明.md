
## 关于各表state以及detail表的说明

四张主要的表有state
- order : 1-待支付, 2-已支付，3-取消, 4-过期
- goods_group : 1-正常 0-不可用
- order_detail : 1-待支付, 2-已支付, 3-已取消，4-已过期
- order_goods : 1-正常, 2-已核销，3-已过期，4-已退
- goods: 1-normal, 0-dead

从我做的"代金券列表"这个需求出发,说明四个state的关系:

order和order_detail相对于order_goods是父子关系
order和order_detail关注订单的支付与否,以及订单的取消和过期
order_goods默认已支付,关注商品的使用与否

而goods_group独立于上面三种,分为可用和不可用

###如果要查询"用户使用过的代金券列表",那么要关注商品核销时,这四张表的状态变化:

- order : 2-已支付
- goods_group : 1-正常
- order_detail : 2-已支付 (goods_used+1,goods_usable-1)
- order_goods : 2-已核销


###如果要查询"用户的过期的代金券列表",那么要关注商品核销时:

我们用一个定时任务来使代金券过期

定时任务来自动过期"代金券"和"活动票"的区别是:

- 前者查询过期的"代金券"
- 后者查询过期的goods_group,因为goods_group和活动的goods的expire_time一样,而代金券是不一样的

"代金券"过期的时候,这四张表的状态变化是:

- order : (不确定)
- goods_group : (不确定)
- order_detail : 4-已过期 (goods_expired = goods_expired+goods_usable,goods_usable = 0)
- order_goods : 之前状态为normal的变为 3-已过期 

"活动票"过期的时候,这四张表的状态变化是:

- order : 4-过期
- goods_group : 0-不可用
- order_detail : 4-已过期 (goods_expired = goods_expired+goods_usable,goods_usable = 0)
- order_goods : 之前状态为normal的变为 3-已过期 
