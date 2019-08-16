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

FilterLoader.getInstance().getFiltersByType(sType); 是获取sType类型的filter. 并按照优先级进行排序.
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

这个filters属性时如何初始化的. 业务自己定义的filter只要交给spring托管, 就可以加载进来. 但是zuul自己提供的10个filter. 就需要FilterFileManager它来负责加载了.

#### pre过滤器

##### ServletDetectionFilter 检测当前请求是通过Spring的DispatcherServlet处理运行，还是通过ZuulServlet来处理运行

优先级 -3. 

##### Servlet30WrapperFilter 将原始的HttpServletRequest包装成Servlet30RequestWrapper对象

优先级 -2

##### FormBodyWrapperFilter 将符合条件的请求包装成FormBodyRequestWrapper对象

优先级 -1

执行条件: application/x-www-form-urlencoded 或者 multipart/form-data 时候执行

##### DebugFilter 将当前RequestContext中的debugRouting和debugRequest参数设置为true

优先级 1

执行条件: 请求中的debug参数（该参数可以通过zuul.debug.parameter来自定义）为true，或者配置参数zuul.debug.request为true时执行

##### PreDecorationFilter 

优先级 5

执行条件: RequestContext不存在forward.to和serviceId两个参数时执行
```java
public class PreDecorationFilter extends ZuulFilter {
@Override
	public Object run() {
//获取请求上下文
		RequestContext ctx = RequestContext.getCurrentContext();
//获取请求路径
		final String requestURI = this.urlPathHelper
				.getPathWithinApplication(ctx.getRequest());
//获取路由信息(CompositeRouteLocator 实在自动装配阶段装配的)
		Route route = this.routeLocator.getMatchingRoute(requestURI);
// 路由存在
		if (route != null) {
//获取路由的定位信息(url或者serviceId)
			String location = route.getLocation();
			if (location != null) {
//设置requestURI= path
				ctx.put(REQUEST_URI_KEY, route.getPath());
//设置proxy = routeId
				ctx.put(PROXY_KEY, route.getId());
//不存在自定义的敏感头信息 设置默认的 ("Cookie", "Set-Cookie", "Authorization")
				if (!route.isCustomSensitiveHeaders()) {
					this.proxyRequestHelper.addIgnoredHeaders(
							this.properties.getSensitiveHeaders().toArray(new String[0]));
				}
//存在 就用用户自己定义的
				else {
					this.proxyRequestHelper.addIgnoredHeaders(
							route.getSensitiveHeaders().toArray(new String[0]));
				}
//设置重试属性
				if (route.getRetryable() != null) {
					ctx.put(RETRYABLE_KEY, route.getRetryable());
				}
//如果location以http或https开头，将其添加到RequestContext的routeHost中，在RequestContext的originResponseHeaders中添加X-Zuul-Service与location的键值对；
				if (location.startsWith(HTTP_SCHEME + ":")
						|| location.startsWith(HTTPS_SCHEME + ":")) {
					ctx.setRouteHost(getUrl(location));
					ctx.addOriginResponseHeader(SERVICE_HEADER, location);
				}
//如果location以forward:开头，则将其添加到RequestContext的forward.to中，将RequestContext的routeHost设置为null并返回；
				else if (location.startsWith(FORWARD_LOCATION_PREFIX)) {
					ctx.set(FORWARD_TO_KEY,
							StringUtils.cleanPath(
									location.substring(FORWARD_LOCATION_PREFIX.length())
											+ route.getPath()));
					ctx.setRouteHost(null);
					return null;
				}
//否则将location添加到RequestContext的serviceId中，将RequestContext的routeHost设置为null，在RequestContext的originResponseHeaders中添加X-Zuul-ServiceId与location的键值对。
				else {
					// set serviceId for use in filters.route.RibbonRequest
					ctx.set(SERVICE_ID_KEY, location);
					ctx.setRouteHost(null);
					ctx.addOriginResponseHeader(SERVICE_ID_HEADER, location);
				}
//如果zuul.addProxyHeaders=true 则在RequestContext的zuulRequestHeaders中添加一系列请求头：X-Forwarded-Host、X-Forwarded-Port、X-Forwarded-Proto、X-Forwarded-Prefix、X-Forwarded-For
				if (this.properties.isAddProxyHeaders()) {
					addProxyHeaders(ctx, route);
					String xforwardedfor = ctx.getRequest()
							.getHeader(X_FORWARDED_FOR_HEADER);
					String remoteAddr = ctx.getRequest().getRemoteAddr();
					if (xforwardedfor == null) {
						xforwardedfor = remoteAddr;
					}
					else if (!xforwardedfor.contains(remoteAddr)) { // Prevent duplicates
						xforwardedfor += ", " + remoteAddr;
					}
					ctx.addZuulRequestHeader(X_FORWARDED_FOR_HEADER, xforwardedfor);
				}
//如果zuul.addHostHeader=ture 则在则在RequestContext的zuulRequestHeaders中添加host
				if (this.properties.isAddHostHeader()) {
					ctx.addZuulRequestHeader(HttpHeaders.HOST,
							toHostHeader(ctx.getRequest()));
				}
			}
		}
//如果 route=null 在RequestContext中将forward.to设置为forwardURI，默认情况下forwardURI为请求路径。              
		else {
			log.warn("No route found for uri: " + requestURI);
			String forwardURI = getForwardUri(requestURI);

			ctx.set(FORWARD_TO_KEY, forwardURI);
		}
		return null;
	}
}
```

#### route过滤器

##### RibbonRoutingFilter 使用Ribbon和Hystrix来向服务实例发起请求，并将服务实例的请求结果返回

优先级 10

执行条件: RequestContext中的routeHost为null，serviceId不为null。sendZuulResponse=true. 即只对通过serviceId配置路由规则的请求生效

使用Ribbon和Hystrix来向服务实例发起请求，并将服务实例的请求结果返回

##### SimpleHostRoutingFilter

优先级 100

执行条件: RequestContext中的routeHost不为null。即只对通过url配置路由规则的请求生效

直接向routeHost参数的物理地址发起请求，该请求是直接通过httpclient包实现的，而没有使用Hystrix命令进行包装，所以这类请求并没有线程隔离和熔断器的保护。

##### SendForwardFilter 获取forward.to中保存的跳转地址，跳转过去

优先级 500

执行条件: RequestContext中的forward.to不为null。即用来处理路由规则中的forward本地跳转配置

#### post过滤器

##### SendResponseFilter 在请求响应中增加头信息（根据设置有X-Zuul-Debug-Header、Date、Content-Type、Content-Length等）：addResponseHeaders;发送响应内容：writeResponse。

优先级 1000

执行条件: 没有抛出异常，RequestContext中的throwable属性为null（如果不为null说明已经被error过滤器处理过了，这里的post过滤器就不需要处理了），并且RequestContext中zuulResponseHeaders、responseDataStream、responseBody三者有一样不为null（说明实际请求的响应不为空）。


##### LocationRewriteFilter 

优先级 SendResponseFilter - 100

执行条件: HttpStatus.valueOf(statusCode).is3xxRedirection() 响应码是3XX的时候执行

功能: 将Location信息转化为Zuul URL.


#### error过滤器

##### SendErrorFilter 

优先级 0

执行条件：RequestContext中的throwable不为null，且sendErrorFilter.ran属性为false。 

在request中设置javax.servlet.error.status_code、javax.servlet.error.exception、javax.servlet.error.message三个属性。将RequestContext中的sendErrorFilter.ran属性设置为true。然后组织成一个forward到API网关/error错误端点的请求来产生错误响应。