```java
 
public class PayServiceStripeImpl implements PayService, ApplicationListener<ApplicationReadyEvent> {

...

@Override
    public void onApplicationEvent(ApplicationReadyEvent applicationReadyEvent) {
        ApplicationContext context = applicationReadyEvent.getApplicationContext();

        Stripe.apiKey = context.getEnvironment().getProperty("stripe.api.key");

        Map<String, StripeEventHandler> handlers = context.getBeansOfType(StripeEventHandler.class);
        handlers.forEach((name, handler) -> {
            String[] events = handler.listenTo();
            if (events != null && events.length > 0) {
                for (String event : events) {
                    handlerMap.put(event, handler);
                }
            }
        });
    }
}
```

