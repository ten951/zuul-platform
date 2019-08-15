# zuul-platform

## 启动zuul网关模块  
这里涉及了spring的注解驱动. 自动配置等相关知识
@EnableZuulProxy-> @import(ZuulProxyMarkerConfiguration.class)-> @Bean就是初始化了Marker类. 相当于打标记

通过Maker类 找到了zuul的自动配置类ZuulProxyAutoConfiguration和父类ZuulServerAutoConfiguration 并在MATA-INF/spring.factories里面找到了这两个类的配置项:
```yaml
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
org.springframework.cloud.netflix.zuul.ZuulServerAutoConfiguration,\
org.springframework.cloud.netflix.zuul.ZuulProxyAutoConfiguration
```
这两个类的加载都是通过@EnableAutoConfiguration完成的.


### ZuulServerAutoConfiguration配置类
```java
@Configuration
//启动zuul属性. 可以理解为加载ZuulProperties
@EnableConfigurationProperties({ ZuulProperties.class })
//条件转载. 需要依赖ZuulServlet和ZuulServletFilter类. 也就是说要依赖zuul-core
@ConditionalOnClass({ ZuulServlet.class, ZuulServletFilter.class })
//上下文环境中必须存在Marker这个Bean.
@ConditionalOnBean(ZuulServerMarkerConfiguration.Marker.class)
public class ZuulServerAutoConfiguration {


	@Bean
     // 缺少zuulServlet Bean时加载
	@ConditionalOnMissingBean(name = "zuulServlet")
    // yml文件中配置的属性zuul.use-filter = false或者没有配置时加载
	@ConditionalOnProperty(name = "zuul.use-filter", havingValue = "false", matchIfMissing = true)
	public ServletRegistrationBean zuulServlet() {
		ServletRegistrationBean<ZuulServlet> servlet = new ServletRegistrationBean<>(
				new ZuulServlet(), this.zuulProperties.getServletPattern());
		// The whole point of exposing this servlet is to provide a route that doesn't
		// buffer requests.
		servlet.addInitParameter("buffer-requests", "false");
		return servlet;
	}

	@Bean
	@ConditionalOnMissingBean(name = "zuulServletFilter")
    //yml文件中配置的属性zuul.use-filter = true. 必须要有这个配置还必须是true 才会加载.
	@ConditionalOnProperty(name = "zuul.use-filter", havingValue = "true", matchIfMissing = false)
	public FilterRegistrationBean zuulServletFilter() {
		final FilterRegistrationBean<ZuulServletFilter> filterRegistration = new FilterRegistrationBean<>();
		filterRegistration.setUrlPatterns(
				Collections.singleton(this.zuulProperties.getServletPattern()));
		filterRegistration.setFilter(new ZuulServletFilter());
		filterRegistration.setOrder(Ordered.LOWEST_PRECEDENCE);
		// The whole point of exposing this servlet is to provide a route that doesn't
		// buffer requests.
		filterRegistration.addInitParameter("buffer-requests", "false");
		return filterRegistration;
	}


}
```
ZuulServlet类和ZuulServletFilter类是zuul提供的两种启动方式, 对应了servlet和servlet Filter.
> [servlet指南](https://www.kancloud.cn/evankaka/servletjsp/119642)

### ZuulServletFilter

这个类告诉了zuul filter的执行顺序
1. init((HttpServletRequest) servletRequest, (HttpServletResponse) servletResponse); 初始化ZuulRunner
2. preRouting() -> zuulRunner.preRoute() -> FilterProcessor.getInstance().preRoute() ->  runFilters("pre"); pre在请求路由之前执行. 业务上可以做一些验证之类的操作
3. routing() ->  zuulRunner.route(); ->  FilterProcessor.getInstance().route() -> runFilters("route"); route 路由请求时调用. 转发请求.
4. postRouting() ->  zuulRunner.postRoute(); -> FilterProcessor.getInstance().postRoute(); ->  runFilters("post"); post: 用来处理响应
5. error(e) -> zuulRunner.error(); -> FilterProcessor.getInstance().error() -> runFilters("error"); error 当错误发生时就会调用这个类型的filter

### ZuulRunner(运行器)类 和 FilterProcessor(执行器)类 真正的核心类

#### ZuulRunner类的作用

1. 调用FilterProcessor
2. 是否要使用HttpServletRequest的包装类HttpServletRequestWrapper(拓展 extends javax.servlet.http.HttpServletRequestWrapper)
提供了一些 方便的API. 比如 HashMap<String, String[]> getParameters()等

#### FilterProcessor类

首先是单例.
```java
public class FilterProcessor {


 public Object runFilters(String sType) throws Throwable {
        if (RequestContext.getCurrentContext().debugRouting()) {
            Debug.addRoutingDebug("Invoking {" + sType + "} type filters");
        }
        boolean bResult = false;
        List<ZuulFilter> list = FilterLoader.getInstance().getFiltersByType(sType);
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                ZuulFilter zuulFilter = list.get(i);
                Object result = processZuulFilter(zuulFilter);
                if (result != null && result instanceof Boolean) {
                    bResult |= ((Boolean) result);
                }
            }
        }
        return bResult;
    }
}
```

FilterLoader.getInstance().getFiltersByType(sType); 是获取sType类型的filter.
其背后调用了FilterRegistry这个类 这个类很简单, 维护了一个ConcurrentHashMap<String, ZuulFilter> filters 容器. 
这两个类的初始化都是在ZuulServerAutoConfiguration这个自动装载的
```java
@Configuration
	protected static class ZuulFilterConfiguration {

		@Autowired
		private Map<String, ZuulFilter> filters;

		@Bean
		public ZuulFilterInitializer zuulFilterInitializer(CounterFactory counterFactory,
				TracerFactory tracerFactory) {
			FilterLoader filterLoader = FilterLoader.getInstance();
			FilterRegistry filterRegistry = FilterRegistry.instance();
			return new ZuulFilterInitializer(this.filters, counterFactory, tracerFactory,
					filterLoader, filterRegistry);
		}

	}
```

这个filters属性时如何初始化的. 最终代码找到了FilterFileManager类和