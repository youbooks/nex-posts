
要搞清楚Apollo合spring框架的结合,了解一下这些类,都是至关重要的
也是我们阅读Apollo源码的基础

```
BeanDefinitionRegistryPostProcessor
ClassPathBeanDefinitionScanner
ApplicationContextInitializer
BeanPostProcessor
BeanFactoryPostProcessor
EnvironmentAware
PriorityOrdered
BeanFactoryAware
BeanDefinition
```

## BeanDefinitionRegistryPostProcessor

BeanDefinitionRegistryPostProcessor接口是一个可以修改spring工厂中已定义的bean的接口，该接口有个postProcessBeanDefinitionRegistry方法。

```
/**
 * Modify the application context's internal bean definition registry after its
 * standard initialization. All regular bean definitions will have been loaded,
 * but no beans will have been instantiated yet. This allows for adding further
 * bean definitions before the next post-processing phase kicks in.
 * [@param](https://my.oschina.net/u/2303379) registry the bean definition registry used by the application context
 * [@throws](https://my.oschina.net/throws) org.springframework.beans.BeansException in case of errors
 */
void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException;
```

Apollo的用法:

```java
/**
 * Apollo Property Sources processor for Spring XML Based Application
 *
 * @author Jason Song(song_s@ctrip.com)
 */
public class ConfigPropertySourcesProcessor extends PropertySourcesProcessor
    implements BeanDefinitionRegistryPostProcessor {

  @Override
  public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
  //注册占位符配置类
    BeanRegistrationUtil.registerBeanDefinitionIfNotExists(registry, PropertySourcesPlaceholderConfigurer.class.getName(),
        PropertySourcesPlaceholderConfigurer.class);
        //注册注解处理类
    BeanRegistrationUtil.registerBeanDefinitionIfNotExists(registry, ApolloAnnotationProcessor.class.getName(),
        ApolloAnnotationProcessor.class);
        //注册springvalue处理类
    BeanRegistrationUtil.registerBeanDefinitionIfNotExists(registry, SpringValueProcessor.class.getName(), SpringValueProcessor.class);
    //注册json处理类
    BeanRegistrationUtil.registerBeanDefinitionIfNotExists(registry, ApolloJsonValueProcessor.class.getName(),
        ApolloJsonValueProcessor.class);

    processSpringValueDefinition(registry);
  }

  /**
   * For Spring 3.x versions, the BeanDefinitionRegistryPostProcessor would not be
   * instantiated if it is added in postProcessBeanDefinitionRegistry phase, so we have to manually
   * call the postProcessBeanDefinitionRegistry method of SpringValueDefinitionProcessor here...
   */
  private void processSpringValueDefinition(BeanDefinitionRegistry registry) {
    SpringValueDefinitionProcessor springValueDefinitionProcessor = new SpringValueDefinitionProcessor();

    springValueDefinitionProcessor.postProcessBeanDefinitionRegistry(registry);
  }
}
```

## ClassPathBeanDefinitionScanner

SpringBoot项目中或者 Spring项目中配置`<context:component-scan base-package="com.example.demo" />`
 ，那么在IOC 容器初始化阶段（调用beanFactoryPostProcessor阶段） 就会采用ClassPathBeanDefinitionScanner进行扫描包下 所有类，并将符合过滤条件的类注册到IOC 容器内。Mybatis 的Mapper注册器(ClassPathMapperScanner) 是同过继承ClassPathBeanDefinitionScanner,并且自定义了过滤器规则来实现的。具体的 调用过程并不会在这里说明，只是想在这里描述ClassPathBeanDefinitionScanner是如何 扫描 和 注册BeanDefinition的。

(具体请看!!!Spring 的类扫描器分析 - ClassPathBeanDefinitionScanner)



## ApplicationContextInitializer

ApplicationContextInitializer是在springboot启动过程(refresh方法前)调用,主要是在ApplicationContextInitializer中initialize方法中拉起了ConfigurationClassPostProcessor这个类(我在springboot启动流程中有描述)，通过这个processor实现了beandefinition。

所以可以利用这个类,来在springboot启动过程中做一些自己的业务逻辑注入

```java
/**
 * Inject the Apollo config in Spring Boot bootstrap phase
 *
 * <p>Configuration example:</p>
 * <pre class="code">
 *   # will inject 'application' namespace in bootstrap phase
 *   apollo.bootstrap.enabled = true
 * </pre>
 *
 * or
 *
 * <pre class="code">
 *   apollo.bootstrap.enabled = true
 *   # will inject 'application' and 'FX.apollo' namespaces in bootstrap phase
 *   apollo.bootstrap.namespaces = application,FX.apollo
 * </pre>
 */
public class ApolloApplicationContextInitializer implements
    ApplicationContextInitializer<ConfigurableApplicationContext> {
  private static final Logger logger = LoggerFactory.getLogger(ApolloApplicationContextInitializer.class);
  private static final Splitter NAMESPACE_SPLITTER = Splitter.on(",").omitEmptyStrings().trimResults();
//得到ConfigPropertySourceFactory
  private final ConfigPropertySourceFactory configPropertySourceFactory = SpringInjector
      .getInstance(ConfigPropertySourceFactory.class);

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    ConfigurableEnvironment environment = context.getEnvironment();
    String enabled = environment.getProperty(PropertySourcesConstants.APOLLO_BOOTSTRAP_ENABLED, "false");
    if (!Boolean.valueOf(enabled)) {
      logger.debug("Apollo bootstrap config is not enabled for context {}, see property: ${{}}", context, PropertySourcesConstants.APOLLO_BOOTSTRAP_ENABLED);
      return;
    }
    logger.debug("Apollo bootstrap config is enabled for context {}", context);

    if (environment.getPropertySources().contains(PropertySourcesConstants.APOLLO_BOOTSTRAP_PROPERTY_SOURCE_NAME)) {
      //already initialized
      return;
    }

      //获取APOLLO_BOOTSTRAP_NAMESPACES命名空间的值
    String namespaces = environment.getProperty(PropertySourcesConstants.APOLLO_BOOTSTRAP_NAMESPACES, ConfigConsts.NAMESPACE_APPLICATION);
    logger.debug("Apollo bootstrap namespaces: {}", namespaces);
    List<String> namespaceList = NAMESPACE_SPLITTER.splitToList(namespaces);

    CompositePropertySource composite = new CompositePropertySource(PropertySourcesConstants.APOLLO_BOOTSTRAP_PROPERTY_SOURCE_NAME);
    for (String namespace : namespaceList) {
        //通过ConfigService获取namespace对应的所有配置(被包装在Config类里面)
      Config config = ConfigService.getConfig(namespace);

      composite.addPropertySource(configPropertySourceFactory.getConfigPropertySource(namespace, config));
    }
//最核心的一行代码,在这里,完成了Apollo配置的注入
    environment.getPropertySources().addFirst(composite);
  }
}
```

上面代码我给出了主要的注释,这里还需要补充一个背景知识,那就是所有spring要用到配置的地方,都是从environment.getPropertySources()里面取的,所以Apollo才会把所有的配置赋值给environment里面的propertySources

## BeanPostProcessor,BeanFactoryPostProcessor和EnvironmentAware

```java
public interface BeanPostProcessor {  
  
    /** 
     * Apply this BeanPostProcessor to the given new bean instance <i>before</i> any bean 
     * initialization callbacks (like InitializingBean's {@code afterPropertiesSet} 
     * or a custom init-method). The bean will already be populated with property values.    
     */  
    //实例化、依赖注入完毕，在调用显示的初始化之前完成一些定制的初始化任务  
    Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException;  
  
      
    /** 
     * Apply this BeanPostProcessor to the given new bean instance <i>after</i> any bean 
     * initialization callbacks (like InitializingBean's {@code afterPropertiesSet}   
     * or a custom init-method). The bean will already be populated with property values.       
     */  
    //实例化、依赖注入、初始化完毕时执行  
    Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException;  
  
}
```

从范围上来说，从上面的所有的bean成为了特定的bean，其次BeanFactoryPostProcessor可以在初始化前修改bean的属性等情况，但是BeanPostProcessor只能在初始化后（注意初始化不包括init方法）执行一些操作。
 **网上很多文章都说BeanPostProcessor不能修改bean属性，其实我看来未必，当其实例化之后，完全可以拿到实例化后的对象，对对象进行一些改值操作也完全可以的**



```java
  public interface BeanFactoryPostProcessor {  
      
        /** 
         * Modify the application context's internal bean factory after its standard 
         * initialization. All bean definitions will have been loaded, but no beans 
         * will have been instantiated yet. This allows for overriding or adding 
         * properties even to eager-initializing beans. 
         * @param beanFactory the bean factory used by the application context 
         * @throws org.springframework.beans.BeansException in case of errors 
         */  
        void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException;  
      
    }  
```

bean工厂的bean属性处理容器，说通俗一些就是可以管理我们的bean工厂内所有的beandefinition（未实例化）数据，可以随心所欲的修改属性。

```java
public interface EnvironmentAware extends Aware {
    void setEnvironment(Environment var1);
}
```

所有xxxAware类都会暴露xxx给调用者,比如EnvironmentAware提供了environment给继承者获取和使用

来看Apollo是怎样使用以上三个类的:

```java
/**
 * Apollo Property Sources processor for Spring Annotation Based Application. <br /> <br />
 *
 * The reason why PropertySourcesProcessor implements {@link BeanFactoryPostProcessor} instead of
 * {@link org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor} is that lower versions of
 * Spring (e.g. 3.1.1) doesn't support registering BeanDefinitionRegistryPostProcessor in ImportBeanDefinitionRegistrar
 * - {@link com.ctrip.framework.apollo.spring.annotation.ApolloConfigRegistrar}
 *
 * @author Jason Song(song_s@ctrip.com)
 */
public class PropertySourcesProcessor implements BeanFactoryPostProcessor, EnvironmentAware, PriorityOrdered {
  private static final Multimap<Integer, String> NAMESPACE_NAMES = LinkedHashMultimap.create();
  private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

  private final ConfigPropertySourceFactory configPropertySourceFactory = SpringInjector
      .getInstance(ConfigPropertySourceFactory.class);
  private final ConfigUtil configUtil = ApolloInjector.getInstance(ConfigUtil.class);
  private ConfigurableEnvironment environment;

  public static boolean addNamespaces(Collection<String> namespaces, int order) {
    return NAMESPACE_NAMES.putAll(order, namespaces);
  }

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
    if (INITIALIZED.compareAndSet(false, true)) {
      initializePropertySources();

      initializeAutoUpdatePropertiesFeature(beanFactory);
    }
  }

  private void initializePropertySources() {
    if (environment.getPropertySources().contains(PropertySourcesConstants.APOLLO_PROPERTY_SOURCE_NAME)) {
      //already initialized
      return;
    }
    CompositePropertySource composite = new CompositePropertySource(PropertySourcesConstants.APOLLO_PROPERTY_SOURCE_NAME);

    //sort by order asc
    ImmutableSortedSet<Integer> orders = ImmutableSortedSet.copyOf(NAMESPACE_NAMES.keySet());
    Iterator<Integer> iterator = orders.iterator();

    while (iterator.hasNext()) {
      int order = iterator.next();
      for (String namespace : NAMESPACE_NAMES.get(order)) {
        Config config = ConfigService.getConfig(namespace);

        composite.addPropertySource(configPropertySourceFactory.getConfigPropertySource(namespace, config));
      }
    }

    // add after the bootstrap property source or to the first
    if (environment.getPropertySources()
        .contains(PropertySourcesConstants.APOLLO_BOOTSTRAP_PROPERTY_SOURCE_NAME)) {
      environment.getPropertySources()
          .addAfter(PropertySourcesConstants.APOLLO_BOOTSTRAP_PROPERTY_SOURCE_NAME, composite);
    } else {
      environment.getPropertySources().addFirst(composite);
    }
  }

  private void initializeAutoUpdatePropertiesFeature(ConfigurableListableBeanFactory beanFactory) {
    if (!configUtil.isAutoUpdateInjectedSpringPropertiesEnabled()) {
      return;
    }

    AutoUpdateConfigChangeListener autoUpdateConfigChangeListener = new AutoUpdateConfigChangeListener(
        environment, beanFactory);

    List<ConfigPropertySource> configPropertySources = configPropertySourceFactory.getAllConfigPropertySources();
    for (ConfigPropertySource configPropertySource : configPropertySources) {
      .addChangeListener(autoUpdateConfigChangeListener);
    }
  }
    .......
```

如同ApolloApplicationContextInitializer,注入config到environment,不同的是,前者注入的是APOLLO_BOOTSTRAP_NAMESPACES命名空间的配置,后者是注入用户自己配置的命名空间的配置值(配置方式有很多种,比如注解和配置文件(/home/opt/apollo/settings)),另外后者给config注册了listener,用来处理配置更改后的逻辑



## PriorityOrdered

Spring中提供了一个Ordered接口。Ordered接口，顾名思义，就是用来排序的。

Spring是一个大量使用策略设计模式的框架，这意味着有很多相同接口的实现类，那么必定会有优先级的问题。

于是，Spring就提供了Ordered这个接口，来处理相同接口实现类的优先级问题。