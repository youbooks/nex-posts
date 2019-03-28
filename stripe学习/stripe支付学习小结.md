### Charge

charge代表发生一次支付行为

![](https://ws3.sinaimg.cn/large/006tNc79gy1ft1kjoytxlj31kw0g0ae3.jpg)

### Source

可以理解为支付方式,公司目前有两种,一是信用卡,二是支付宝

- 这种支付方式的共同点:

都是遵照上图进行charge,只是source字段的值有所区别

- 不同点:

信用卡的source是reusable的,stripe规定reusable的source必须要和Customer进行绑定,而支付宝是single-use,每次都需要用户去验证,然后stripe通过webhook的方式,回调我们的接口,在回调中,我们才能拿到source的id

### Webhook

异步支付如支付宝会通过webhook的方式给后端发Event对象,Event有很多种状态,当满足StripeStatus.Source.CHARGEABLE的时候,代表可以进行支付

![](https://ws2.sinaimg.cn/large/006tNc79gy1ft1kqypus3j31kw15ah3h.jpg)

(注:Event里面通过event.getData().getObject()获取Source)

### 信用卡支付

在目前的mall服务中,信用卡支付因为是reusable,所以要把绑定的卡信息存起来,

分别存在MySQL和stripe

![](https://ws3.sinaimg.cn/large/006tKfTcgy1ftbjxtx5bbj31kw18vaj1.jpg)

在前端支付页面渲染的时候,可以返回用户绑定的卡(其中最关键的cardId,也就是charge方法中source字段的值,有它才能进行同步的支付)

![](https://ws3.sinaimg.cn/large/006tKfTcgy1ft1l1gcvarj31kw1b2k24.jpg)

参考资料:

https://stripe.com/docs