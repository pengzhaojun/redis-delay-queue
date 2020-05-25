package com.shirc.demo.config;

import com.yuanjing.newsports.config.redis.configuration.AbstractRedisConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * redis多实例 ,此处如果有多个redis即注册多个redis
 *
 * @author: smart
 */
@Configuration
public class MultipleRedisConfiguration extends AbstractRedisConfiguration {

    @Bean({"redisTemplate"})
    @Primary
    public RedisTemplate<String, Object> getRedisTemplate() {
        return this.buildRedisTemplate("default");
    }
}