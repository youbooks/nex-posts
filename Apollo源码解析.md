![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0lw5cautnj311q0tujxn.jpg)



![](https://ww1.sinaimg.cn/large/007iUjdily1g0lw5qdf17j319e0t444q.jpg)

要搞清config的注入时机,不深刻理解Spring中的**ImportBeanDefinitionRegistrar**,**ApplicationContextInitializer**和**NamespaceHandlerSupport**是不行的,所以下面会从源码的角度来深入spring boot的这三个"钩子"是怎样工作的(这一部分之后会被移植到Spring源码解析的系列文章中)