package com.ten951.learn.zuul;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.zuul.EnableZuulProxy;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * @author yongtianwang
 */
@SpringBootApplication
@EnableWebMvc
@EnableZuulProxy
public class ZuulPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZuulPlatformApplication.class, args);
    }





}
