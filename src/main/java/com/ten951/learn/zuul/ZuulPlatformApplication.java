package com.ten951.learn.zuul;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.zuul.EnableZuulProxy;

/**
 * @author yongtianwang
 */
@SpringBootApplication
@EnableZuulProxy
public class ZuulPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZuulPlatformApplication.class, args);
    }

}
