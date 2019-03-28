# about stripe connect

![image-20181029213103216](/Users/wangwang/Library/Application Support/typora-user-images/image-20181029213103216.png)



![img](https://stripe.com/img/docs/connect/overview.png)



#  connect types

![image-20181029213215631](/Users/wangwang/Library/Application Support/typora-user-images/image-20181029213215631.png)



# Creating charges

![image-20181029213429859](/Users/wangwang/Library/Application Support/typora-user-images/image-20181029213429859.png)


```java

// Set your secret key: remember to change this to your live secret key in production
// See your keys here: https://dashboard.stripe.com/account/apikeys
Stripe.apiKey = "sk_test_RZqXajaHj9YDVmlHVY9gau4p";

Map<String, Object> params = new HashMap<String, Object>();
params.put("amount", 1000);
params.put("currency", "usd");
params.put("source", "tok_visa");

RequestOptions requestOptions = RequestOptions.builder().setStripeAccount({CONNECTED_STRIPE_ACCOUNT_ID}).build();
Charge charge = Charge.create(params, requestOptions);
```

https://dashboard.stripe.com/test/connect/accounts/acct_1DPPTMF3fnc753ga

https://dashboard.stripe.com/acct_1DPPTMF3fnc753ga/test/dashboard

