学习源码过程中，对各种context（上下文）表示很懵逼。特地留此一篇。

1.要了解各个上下文之间的关系。首先走一遍spring在web容器(tomcat)中的启动过程

 a) ServletContext:  tomcat启动会创建一个ServletContext，作为全局上下文以及spring容器的宿主环境。当执行Servlet的init()方法时，会触发ServletContextListener的 public void contextInitialized(ServletContextEvent sce);方法

![img](https://images2015.cnblogs.com/blog/1127727/201706/1127727-20170614201633978-166532039.png)

b)WebApplicationContext:  在web.xml(上图)中我们配置了ContextLoaderListener，该listener实现了ServletContextListener的contextInitialized方法用来监听Servlet初始化事件。

​     下图中红框部门的注释解释了该方法的作用。即初始化根上下文（即IOC容器），也就是WebApplicationContext。该类是一个接口类，其默认实现为XmlWebApplicationContext。

 ![img](https://images2015.cnblogs.com/blog/1127727/201706/1127727-20170614205603071-606742686.png)

在initWebApplicationContext这个方法中进行了创建根上下文，并将该上下文以key-value的方式存储到ServletContext中![img](https://images2015.cnblogs.com/blog/1127727/201706/1127727-20170614210443181-2113400544.png)

以WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE为key，this.context则为value。this.context就是刚才创建的根上下文。后面就可以通过这个ServletContext通过这个key获取该上下文了。而在web.xml中还有一对重要的标签

<context-param>该标签内的<param-name>的值是固定的原因在这张图上。该常量的值就是contextConfigLocation。通过该方法去寻找定义spring的xml文件。来初始化IOC容器的相关信息。![img](https://images2015.cnblogs.com/blog/1127727/201706/1127727-20170614212008587-1930585965.png)

 

 c) DispatcherServlet的上下文:  在WebApplicationContext初始化完后。开始初始化web.xml中的servlet。这个servlet可以有多个。默认我们都使用DispatcherServlet。<servlet>标签中可以有<init-param>标签用来配置一些DispatcherServlet的初始化参数。

   该servlet初始化流程是有tomcat的Servlet的init()方法触发。DispatcherServleet-继承->FrameworkServlet-继承->HttpServletBean-继承-GenericServlet- 实现 ->Servlet。这样的一条关系链。其核心方法在FrameworkServlet中的initServletBean()中

   中的initWebApplicationContext()方法中。

![img](https://images2015.cnblogs.com/blog/1127727/201706/1127727-20170614213829696-407022881.png)

![img](https://images2015.cnblogs.com/blog/1127727/201706/1127727-20170614213907243-851750238.png)

initWebApplicationContext()方法中的第一个红色框内就是去获取之前存在Servlet中的WebApplicationContext。通过上面说的WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE作为key

取到之后，设置为当前DispatcherServlet的父上下文。并且也把该上下文存在ServletContext中。方法如下

![img](https://images2015.cnblogs.com/blog/1127727/201706/1127727-20170614214253728-452803971.png)

 

 2.

   a).   通过以上的流程，可以做到各个上下文之间既可以拥有自己独立的Bean，也可以访问各个Servlet相同的Bean

   b). 通过init方法创建的dispatcherServlet上下文可以访问通过ServletContextListener中创建的WebApplicationContext上下文中的bean，反之则不行。因为WebApplicationContext是dispatcherServlet上下文的父容器。

3. [api文档](http://tool.oschina.net/uploads/apidocs/Spring-3.1.1/org/springframework/web/context/WebApplicationContext.html)

![img](https://images2015.cnblogs.com/blog/1127727/201706/1127727-20170614214801540-1541859964.png)

 

### 小结

web容器可以说就是Servlet容器--ServletContext，启动tomcat必然有这个。

dispatcherServlet只是一个Servlet，必然装在容器里。当然容器可以装其它任何Servlet，不一定必须有dispatcherServlet。

WebApplicationContext是IOC容器，里面是装spring的bean的。可以说与上面的容器及具体的Servlet没有直接联系。

但是具体的Servlet一般都会使用IOC容器里的东西，所以两个容器之间要有直接引用关系。但是容器里的内容不应该有直接的引用关系。

所以WebApplicationContext会放在ServletContext中，这个过程是监听Servlet容器后，产生IOC容器，并放置在里面的。