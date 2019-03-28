# Dealmoon支付服务接入规范

### 1、文档说明

支付功能最先于2018年2月份应用于Local的线下活动线上买票业务，后面以此为基础在2018年8月份扩展到了代金券项目。随后的拼团项目也欲使用支付功能，因此，有必要将支付功能设计成独立的高度可复用的功能模块，为Dealmoon全站提供便捷的支付功能支持。

### 2、整体架构

![payment-process-flow](http://thumbimg.dealmoon.com/dealmoon/410/cc0/d12/d99b6f963f46f38c793747a.png)

整个支付服务由客户端（Client：IOS、Android、Web、Wap）、业务服务端（Biz Service）、支付服务（Pay Service）协同完成：

1、客户端提交订单到业务服务，业务服务处理订单，并调用支付服务提供的预支付接口，创建预支付订单；得到预支付订单后，业务服务应该将预支付订单与业务订单关联并存储，以便查询支付状态和往后的账目对比；然后，业务服务端需要将预支付信息返回给客户端，由客户端来发起支付。

2、客户端拿到业务服务端返回的预支付信息，需要根据用户选择的支付方式做不同处理。

​	2.1、如果用户选择的是信用卡，则直接使用预支付的ID和信用卡ID，调用支付服务的支付接口进行支付。

​	2.2、如果用户选择的是支付宝，则需要先使用Stripe的SDK创建支付Source，使用预支付的ID和Source ID，调用支付服务的支付接口进行支付。

3、支付服务收到支付请求后，会进行扣款操作，并同步返回支付结果。

4、业务服务通过提供一个https地址来接收支付状态变化的通知。当然，支付服务也提供https接口，供业务服务来主动拉取支付状态。

### 3、接入流程

#### 3.1、创建支付应用

业务服务使用支付功能之前，需要先创建支付应用。目前这个创建过程，由支付服务提供者手动执行。**业务方需要提供以下数据，并联系支付服务提供者**：

| 项              | 说明                                                         |
| --------------- | ------------------------------------------------------------ |
| 业务服务名      | 支付服务使用者的服务标识                                     |
| 支付状态通知url | 接收支付状态通知的https接口，支付服务将会把支付状态的变更Post到这个接口 |

支付服务提供方会生成用于支付的appId和appKey，业务服务在使用支付服务时，需要用到这个appId和appKey。

#### 3.2、发起预支付

业务服务端调用

```
curl -XPOST https://mall.dealmoon.com/api/mall/v1/prepay?dm_sign=sljfqjwr
```

请求url中的参数dm_sign，为请求签名，用于验证请求有效性。详情参考《**5.1、支付安全-签名**》

请求的RequestBody为Json字符串，字段说明如下：

| 字段     | 类型         | 必填 | 说明                                          |
| -------- | ------------ | ---- | --------------------------------------------- |
| appId    | String(32)   | 是   | Required。上一步生成的appId                   |
| orderId  | String(32)   | 是   | 业务方生成的订单ID，对于同一个appId，必须唯一 |
| amount   | INT          | 是   | 支付金额，以分为单位                          |
| currency | String(3)    | 否   | 币种，国际通用币种标识，默认USD-美元          |
| desc     | String(1000) | 是   | 订单描述，业务方自定义                        |
| body     | String(1000) | 否   | 订单内容，用于业务方自定义存放一些数据        |
| userId   | String(32)   | 是   | 支付的用户ID（待完善）                        |

返回：预支付接口返回为Json格式。

**成功时返回：**

| 字段    | 类型       | 说明               |
| ------- | ---------- | ------------------ |
| success | Boolean    | 成功时固定返回true |
| data    | JsonObject | 预支付信息         |

**失败时返回：**

| 字段    | 类型    | 说明                |
| ------- | ------- | ------------------- |
| success | Boolean | 失败时固定返回false |
| code    | String  | 错误码              |
| message | String  | 失败原因            |

**预支付信息：**

| 字段     | 类型       | 说明       |
| -------- | ---------- | ---------- |
| serialNumber | String(32) | 预支付ID(流水号)   |
| amount   | INT        | 支付金额   |
| orderId  | String(32) | 业务订单号 |

**请求示例：**

```
curl -H 'Content-Type:application/json;charset=utf-8' -XPOST 'https://mall.dealmoon.com/api/mall/v1/prepay?dm_sign=sljfqjwr' -d '{
    "amount":16800,
    "desc":"拼团-飞利浦电动牙刷",
    "currency":"USD",
    "orderId":"ugc201808161402143854",
    "appId":"publicTest",
    "body":"{\"userComment\":\"急用，请尽快发货发货\"}",
    "userId":"481889"
}'
```

**返回示例：**

```
成功：
{
    "success":true,
    "data":{
    	"serialNumber":"20180816162513147154323523452345",
    	"amount":16800,
    	"orderId":"ugc201808161402143854"
    }
}

失败：
{
    "success":false,
    "code":"45105",
    "message":"签名校验失败"
}
```

接口说明:
返回的serialNumber,请业务调用方进行保存,因为后面的支付和退款都需要用到

#### 3.3、发起支付

客户端调用

```
curl -XPOST https://mall.dealmoon.com/api/mall/v1/pay
```

请求的RequestBody为Json字符串，字段说明如下：

| 字段     | 类型         | 必填 | 说明                                          |
| -------- | ------------ | ---- | --------------------------------------------- |
| serialNumber    | String(32)   | 是   | prepay接口返回,用来标识一次支付行为|
| payMethod  |String(10) | 是   | 支付方式(是一个枚举,如card,apliay,wechat) |
| source   | String(32)           | 是   | stripe中Source的id(信用卡支付,source就为cardId,其他支付stripe会返回source对象,取id即可)                      |

**成功时返回：**

| 字段    | 类型       | 说明               |
| ------- | ---------- | ------------------ |
| success | Boolean    | 成功时固定返回true |
| data    | JsonObject | 支付状态信息       |

**失败时返回：**

| 字段    | 类型    | 说明                |
| ------- | ------- | ------------------- |
| success | Boolean | 失败时固定返回false |
| code    | String  | 错误码              |
| message | String  | 失败原因            |

**支付状态信息：**

| 字段   | 类型       | 说明                                     |
| ------ | ---------- | ---------------------------------------- |
| state  | String(10) | 支付状态(是一个枚举:paid,pending,failed) |

**请求示例：**

```
curl -H 'Content-Type:application/json;charset=utf-8' -XPOST 'https://mall.dealmoon.com/api/mall/v1/pay' -d '{
    "serialNumber":"20180816162513147154323523452345",
    "payMethod":"card",
    "source":"card_1CKjBdEwuozWXpIP1zliZYuB"
}'
```

**返回示例：**

```
成功：
{
    "success":true,
    "data":{
    	"state":"paid"
    }
}

失败：
{
    "success":false,
    "code":"45105",
    "message":"error msg..."
}
```

#### 3.4、接收支付状态变更通知

支付服务调用接入时约定的回调接口,这里给出一个回调接口的示例:

```
curl -XPOST https://publicTest.dealmoon.com/api/publicTest/v1/pay-call-back
```

请求的RequestBody为Json字符串，字段说明如下：

| 字段     | 类型         | 必填 | 说明                                          |
| -------- | ------------ | ---- | --------------------------------------------- |
| data    | JsonObject  | 是   | 支付服务返回的支付信息和支付结果信息|

**成功时返回：**

| 字段    | 类型       | 说明               |
| ------- | ---------- | ------------------ |
| success | Boolean    | 成功时固定返回true |

**失败时返回：**

| 字段    | 类型    | 说明                |
| ------- | ------- | ------------------- |
| success | Boolean | 失败时固定返回false |
| code    | String  | 错误码              |
| message | String  | 失败原因            |

**请求示例：**

```
curl -H 'Content-Type:application/json;charset=utf-8' -XPOST 'https://publicTest.dealmoon.com/api/publicTest/v1/pay-call-back' -d '{
    "data":{
    "serialNumber":"20180816162513147154323523452345",
    "state":"paid"
    }
}'
```

**返回示例：**

```
成功：
{
    "success":true,
}

失败：
{
    "success":false,
    "code":"45105",
    "message":"callback error msg..."
}
```

**接口说明**

提供回调接口的业务端,同样需要对回调接口进行合法性验证,使用的方法和预支付接口相同

#### 3.5、主动获取支付状态

业务服务端调用

```
curl https://mall.dealmoon.com/api/mall/v1/getPayState?serialNumber=xxx
```

字段说明如下：

| 字段     | 类型         | 必填 | 说明                                          |
| -------- | ------------ | ---- | --------------------------------------------- |
| serialNumber    | String(32)   | 是   | 序列号,用来标识一次支付行为|


**成功时返回：**

| 字段    | 类型       | 说明               |
| ------- | ---------- | ------------------ |
| success | Boolean    | 成功时固定返回true |
| data | JsonObject | 支付状态信息 |


**失败时返回：**

| 字段    | 类型    | 说明                |
| ------- | ------- | ------------------- |
| success | Boolean | 失败时固定返回false |
| code    | String  | 错误码              |
| message | String  | 失败原因            |

**请求示例：**

```
curl -H 'Content-Type:application/json;charset=utf-8' -XPOST 'https://mall.dealmoon.com/api/mall/v1/getPayState' -d '{
    "serialNumber":"20180816162513147154323523452345"    }'
```

**返回示例：**

```
成功：
{
    "success":true,
    "data:{
        "state":"paid"
    }
}

失败：
{
    "success":false,
    "code":"45105",
    "message":"getPayState error msg..."
}
```



#### 3.6、发起退款

业务服务端调用

```
curl -XPOST https://mall.dealmoon.com/api/mall/v1/refund
```

请求的RequestBody为Json字符串，字段说明如下：

| 字段     | 类型         | 必填 | 说明                                          |
| -------- | ------------ | ---- | --------------------------------------------- |
| serialNumber    | String(32)   | 是   | 序列号,用来标识一次支付行为|
| amount    | int   | 是   | 退款金额,单位为分|


**成功时返回：**

| 字段    | 类型       | 说明               |
| ------- | ---------- | ------------------ |
| success | Boolean    | 成功时固定返回true |

**失败时返回：**

| 字段    | 类型    | 说明                |
| ------- | ------- | ------------------- |
| success | Boolean | 失败时固定返回false |
| code    | String  | 错误码              |
| message | String  | 失败原因            |

**请求示例：**

```
curl -H 'Content-Type:application/json;charset=utf-8' -XPOST 'https://mall.dealmoon.com/api/mall/v1/refund' -d '{
    "serialNumber":"20180816162513147154323523452345",
    "amount":1000
    }'
```

**返回示例：**

```
成功：
{
    "success":true,
}

失败：
{
    "success":false,
    "code":"45105",
    "message":"refund error msg..."
}
```

** 接口说明: **

退款接口也要进行请求合法性验证, 验证方法和预支付一致

#### 3.7、批量获取支付信息

业务服务端调用

```
curl https://mall.dealmoon.com/api/mall/v1/getPayInfoByBatch
```

| 字段     | 类型         | 必填 | 说明                                          |
| -------- | ------------ | ---- | --------------------------------------------- |
| transactionIds    | String(32)   | 否   | 多个交易号使用逗号分隔 |
| startTime    | String(32)   | 否   | 多个交易号使用逗号分隔 |
| endTime    | String(32)   | 否   | 多个交易号使用逗号分隔 |
| appId    | String(32)   | 否   | 多个交易号使用逗号分隔 |


**成功时返回：**

| 字段    | 类型       | 说明               |
| ------- | ---------- | ------------------ |
| success | Boolean    | 成功时固定返回true |
| data | jsonObject    | 支付信息数组 |

**失败时返回：**

| 字段    | 类型    | 说明                |
| ------- | ------- | ------------------- |
| success | Boolean | 失败时固定返回false |
| code    | String  | 错误码              |
| message | String  | 失败原因            |

**请求示例1：**

```
curl -H 'Content-Type:application/json;charset=utf-8' -XPOST 'https://mall.dealmoon.com/api/mall/v1/getPayInfoByBatch' -d '{    "transactionIds":"20180816162513147154323523452345,20180816162513147154323523222345"
    }'
```

**请求示例1：**

```
curl -H 'Content-Type:application/json;charset=utf-8' -XPOST 'https://mall.dealmoon.com/api/mall/v1/getPayInfoByBatch' -d '{
    "startTime":1534409974000,
    "endTime":1539409974000,
    "appId":"publicTest"
    }'
```

**返回示例：**

```
成功：
{
    "success":true,
    "data:[
        {
            "id":222222,
            "transactionId":"20180816162513147154323523452345",
            "appId":"publicTest",
            "amount":10000,
            "state":1
        }
    ]
}

失败：
{
    "success":false,
    "code":"45105",
    "message":"getPayInfoByBatch error msg..."
}
```

### 4、其他常用接口

#### 4.1、获取Stripe临时会话

```
curl https://mall.dealmoon.com/api/mall/v1/getEphemeralKey?apiVersion=1.2
```

字段说明如下：

| 字段     | 类型         | 必填 | 说明                                          |
| -------- | ------------ | ---- | --------------------------------------------- |
| apiVersion    | String(32)   | 是   | api的版本号|

**成功时返回：**

| 字段    | 类型       | 说明               |
| ------- | ---------- | ------------------ |
| success | Boolean    | 成功时固定返回true |
| data    | JsonObject | 临时凭证       |

**失败时返回：**

| 字段    | 类型    | 说明                |
| ------- | ------- | ------------------- |
| success | Boolean | 失败时固定返回false |
| code    | String  | 错误码              |
| message | String  | 失败原因            |

**请求示例：**

```
curl -H 'Content-Type:application/json;charset=utf-8' 'https://mall.dealmoon.com/api/mall/v1/getEphemeralKey?apiVersion=1.2'
```

**返回示例：**

```
成功：
{
    "success":true,
    "data": {
        "ephemeralKey":"xxx"
    }
}

失败：
{
    "success":false,
    "code":"45105",
    "message":"getEphemeralKey error msg..."
}
```

#### 4.2、添加信用卡

```
curl -XPOST https://mall.dealmoon.com/api/mall/v1/addCard
```

请求的RequestBody为Json字符串，字段说明如下：

| 字段     | 类型         | 必填 | 说明                                          |
| -------- | ------------ | ---- | --------------------------------------------- |
| cardId    | String(32)   | 是   | cardId|

**成功时返回：**

| 字段    | 类型       | 说明               |
| ------- | ---------- | ------------------ |
| success | Boolean    | 成功时固定返回true |
| data    | String(50) | 临时凭证       |

**失败时返回：**

| 字段    | 类型    | 说明                |
| ------- | ------- | ------------------- |
| success | Boolean | 失败时固定返回false |
| code    | String  | 错误码              |
| message | String  | 失败原因            |

**请求示例：**

```
curl -H 'Content-Type:application/json;charset=utf-8' -XPOST 'https://mall.dealmoon.com/api/mall/v1/addCard' -d '{
    "cardId":"xxx"
}'
```

**返回示例：**

```
成功：
{
    "success":true
}

失败：
{
    "success":false,
    "code":"45105",
    "message":"addCard error msg..."
}
```


#### 4.3、修改信用卡

```
curl -XUPDATE https://mall.dealmoon.com/api/mall/v1/updateCard
```

请求的RequestBody为Json字符串，字段说明如下：

| 字段     | 类型         | 必填 | 说明                                          |
| -------- | ------------ | ---- | --------------------------------------------- |
| cardId    | String(32)   | 是   | cardId|

**成功时返回：**

| 字段    | 类型       | 说明               |
| ------- | ---------- | ------------------ |
| success | Boolean    | 成功时固定返回true |
| data    | String(50) | 临时凭证       |

**失败时返回：**

| 字段    | 类型    | 说明                |
| ------- | ------- | ------------------- |
| success | Boolean | 失败时固定返回false |
| code    | String  | 错误码              |
| message | String  | 失败原因            |

**请求示例：**

```
curl -H 'Content-Type:application/json;charset=utf-8' -XPUT 'https://mall.dealmoon.com/api/mall/v1/updateCard' -d '{
    "cardId":"xxx"
    }'
```

**返回示例：**

```
成功：
{
    "success":true
}

失败：
{
    "success":false,
    "code":"45105",
    "message":"updateCard error msg..."
}
```


#### 4.4、删除信用卡

```
curl -XDELETE https://mall.dealmoon.com/api/mall/v1/deleteCard
```

请求的RequestBody为Json字符串，字段说明如下：

| 字段     | 类型         | 必填 | 说明                                          |
| -------- | ------------ | ---- | --------------------------------------------- |
| cardId    | String(32)   | 是   | cardId|

**成功时返回：**

| 字段    | 类型       | 说明               |
| ------- | ---------- | ------------------ |
| success | Boolean    | 成功时固定返回true |
| data    | String(50) | 临时凭证       |

**失败时返回：**

| 字段    | 类型    | 说明                |
| ------- | ------- | ------------------- |
| success | Boolean | 失败时固定返回false |
| code    | String  | 错误码              |
| message | String  | 失败原因            |

**请求示例：**

```
curl -H 'Content-Type:application/json;charset=utf-8' -XDELETE 'https://mall.dealmoon.com/api/mall/v1/deleteCard' -d '{
    "cardId":"xxx"
 }'
```

**返回示例：**

```
成功：
{
    "success":true
}

失败：
{
    "success":false,
    "code":"45105",
    "message":"updateCard error msg..."
}
```

#### 4.5、获取可用的支付方式

```
curl https://mall.dealmoon.com/api/mall/v1/payMethods
```

**成功时返回：**

| 字段    | 类型       | 说明               |
| ------- | ---------- | ------------------ |
| success | Boolean    | 成功时固定返回true |
| data    | JsonArray | 支付方式的数组,包含了所有支持的支付方式       |

**失败时返回：**

| 字段    | 类型    | 说明                |
| ------- | ------- | ------------------- |
| success | Boolean | 失败时固定返回false |
| code    | String  | 错误码              |
| message | String  | 失败原因            |

**请求示例：**

```
curl -H 'Content-Type:application/json;charset=utf-8' 'https://mall.dealmoon.com/api/mall/v1/payMethods'
```

**返回示例：**

```
成功：
{
    "success":true,
    "data":[
        {
            "id":1,
            "name":"card",
            "platform":"pc,ios,android"
        }
    ]
}

失败：
{
    "success":false,
    "code":"45105",
    "message":"payMethods error msg..."
}
```

#### 4.6、获取用户的信用卡列表

```
curl https://mall.dealmoon.com/api/mall/v1/cardList
```

**成功时返回：**

| 字段    | 类型       | 说明               |
| ------- | ---------- | ------------------ |
| success | Boolean    | 成功时固定返回true |
| data    | JsonArray | 信用卡的数组,包含了所有卡的信息    |

**失败时返回：**

| 字段    | 类型    | 说明                |
| ------- | ------- | ------------------- |
| success | Boolean | 失败时固定返回false |
| code    | String  | 错误码              |
| message | String  | 失败原因            |

**请求示例：**

```
curl -H 'Content-Type:application/json;charset=utf-8' 'https://mall.dealmoon.com/api/mall/v1/cardList'
```

**返回示例：**

```
成功：
{
    "success":true,
    "data":[
        {
            "id":1,
            "cardId":"card_1C4fgEEwuozWXpIP9x0lGcJf"
        }
    ]
}

失败：
{
    "success":false,
    "code":"45105",
    "message":"getCardList error msg..."
}
```



### 5、支付安全

#### 5.1、签名

第一步: 
使用fastjson的JSON.toJSONString()方法将requestBody转为json字符串
拼接上面的json字符串和appKey得到一个字符串

(注意:请统一使用fastjson的JSON.toJSONString(),因为fastjson.toJSONString默认按字符字母排列顺序输出)

第二步:
使用HmacSha256加密算法(mall服务的com.dealmoon.common.utils.codec/使用HmacSha256加密算法),对上一步得到的字符串进行加密
