## 二 抢购场景限流

譬如我们预估数据库能承受并发10，超过了可能会造成故障，我们就可以对该请求接口进行限流。

```java
package com.tianyalei.controller;  
  
import com.google.common.util.concurrent.RateLimiter;  
import com.tianyalei.model.GoodInfo;  
import com.tianyalei.service.GoodInfoService;  
import org.springframework.web.bind.annotation.RequestMapping;  
import org.springframework.web.bind.annotation.RestController;  
  
import javax.annotation.Resource;  
  
/** 
 * Created by wuwf on 17/7/11. 
 */  
@RestController  
public class IndexController {  
    @Resource(name = "db")  
    private GoodInfoService goodInfoService;  
  
    RateLimiter rateLimiter = RateLimiter.create(10);  
  
    @RequestMapping("/miaosha")  
    public Object miaosha(int count, String code) {  
        System.out.println("等待时间" + rateLimiter.acquire());  
        if (goodInfoService.update(code, count) > 0) {  
            return "购买成功";  
        }  
        return "购买失败";  
    }  
  
  
  
    @RequestMapping("/add")  
    public Object add() {  
        for (int i = 0; i < 100; i++) {  
            GoodInfo goodInfo = new GoodInfo();  
            goodInfo.setCode("iphone" + i);  
            goodInfo.setAmount(100);  
            goodInfoService.add(goodInfo);  
        }  
  
        return "添加成功";  
    }  
}  
```

![mage-20180412112932](/var/folders/hr/c1f08q_n0msf4gp05cdnc04c0000gn/T/abnerworks.Typora/image-201804121129328.png)

## 抢购场景降级

上面的例子虽然限制了单位时间内对DB的操作，但是对用户是不友好的，因为他需要等待，不能迅速的得到响应。当你有1万个并发请求，一秒只能处理10个，那剩余的用户都会陷入漫长的等待。所以我们需要对应用降级，一旦判断出某些请求是得不到令牌的，就迅速返回失败，避免无谓的等待。

由于RateLimiter是属于单位时间内生成多少个令牌的方式，譬如0.1秒生成1个，那抢购就要看运气了，你刚好是在刚生成1个时进来了，那么你就能抢到，在这0.1秒内其他的请求就算白瞎了，只能寄希望于下一个0.1秒，而从用户体验上来说，不能让他在那一直阻塞等待，所以就需要迅速判断，该用户在某段时间内，还有没有机会得到令牌，这里就需要使用tryAcquire(long timeout, TimeUnit unit)方法，指定一个超时时间，一旦判断出在timeout时间内还无法取得令牌，就返回false。注意，这里并不是真正的等待了timeout时间，而是被判断为即便过了timeout时间，也无法取得令牌。这个是不需要等待的。

```java
/** 
     * tryAcquire(long timeout, TimeUnit unit) 
     * 从RateLimiter 获取许可如果该许可可以在不超过timeout的时间内获取得到的话， 
     * 或者如果无法在timeout 过期之前获取得到许可的话，那么立即返回false（无需等待） 
     */  
    @RequestMapping("/buy")  
    public Object miao(int count, String code) {  
        //判断能否在1秒内得到令牌，如果不能则立即返回false，不会阻塞程序  
        if (!rateLimiter.tryAcquire(1000, TimeUnit.MILLISECONDS)) {  
            System.out.println("短期无法获取令牌，真不幸，排队也瞎排");  
            return "失败";  
        }  
        if (goodInfoService.update(code, count) > 0) {  
            System.out.println("购买成功");  
            return "成功";  
        }  
        System.out.println("数据不足，失败");  
        return "失败";  
    } 
```

![mage-20180412113006](/var/folders/hr/c1f08q_n0msf4gp05cdnc04c0000gn/T/abnerworks.Typora/image-201804121130069.png)



当我修改为2秒内产生100个请求时，结果就更平均了

![mage-20180412113023](/var/folders/hr/c1f08q_n0msf4gp05cdnc04c0000gn/T/abnerworks.Typora/image-201804121130238.png)



基本上就是前10个成功，后面的就开始按照固定的速率而成功了。

这种场景更符合实际的应用场景，按照固定的单位时间进行分割，每个单位时间产生一个令牌，可供购买。





