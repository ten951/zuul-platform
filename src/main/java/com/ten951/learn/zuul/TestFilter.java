package com.ten951.learn.zuul;

import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.exception.ZuulException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * @author Darcy
 * @date 2019-08-15 19:12
 */
@Component
public class TestFilter extends ZuulFilter {

    private static Logger logger = LoggerFactory.getLogger(TestFilter.class);

    @Override
    public String filterType() {
        return "pre";
    }

    @Override
    public int filterOrder() {
        return 0;
    }

    @Override
    public boolean shouldFilter() {
        return true;
    }

    @Override
    public Object run() throws ZuulException {
        logger.info("执行了");
        return null;
    }
}
