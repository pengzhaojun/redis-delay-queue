package com.shirc.redis.delay.queue.redis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.shirc.redis.delay.queue.common.Args;
import com.shirc.redis.delay.queue.utils.ExceptionUtil;
import com.shirc.redis.delay.queue.utils.RedisKeyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.util.StringUtils;
import redis.clients.jedis.JedisCluster;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Description redis的操作类, 不过都是执行的Lua脚本
 * @Author shirenchuang
 * @Date 2019/8/1 3:20 PM
 **/
public class RedisOperationByLua extends RedisOperationByNormal {

    private static final Logger logger = LoggerFactory.getLogger(RedisOperationByLua.class);

    public RedisOperationByLua(RedisTemplate redisTemplate) {
        super(redisTemplate);
    }


    @Override
    public void addJob(String topic, Args arg, long runTimeMillis) {

        List<String> keys = Lists.newArrayList();
        keys.add(RedisKeyUtil.getDelayQueueTableKey());
        keys.add(RedisKeyUtil.getBucketKey());
        DefaultRedisScript redisScript = new DefaultRedisScript<>();
        redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/addJob.lua")));
        List<String> args = Arrays.asList(RedisKeyUtil.getTopicId(topic, arg.getId()), JSONObject.toJSONString(arg), String.valueOf(runTimeMillis));
        this.execute(redisScript.getScriptAsString(), keys, args);
        logger.info("新增延时任务:Topic:{};id:{},runTimeMillis:{},Args={}", topic, arg.getId(), runTimeMillis, arg.toString());

    }

    @Override
    public Args getJob(String topicId) {
        List<String> keys = new ArrayList<>(1);
        keys.add(RedisKeyUtil.getDelayQueueTableKey());
        DefaultRedisScript<Args> redisScript = new DefaultRedisScript<>();
        redisScript.setResultType(Args.class);
        redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/getJob.lua")));
        Object args = this.execute(redisScript.getScriptAsString(), keys, Arrays.asList(topicId));
        if (args == null) return null;
        return JSON.parseObject(args.toString(), Args.class);
    }

    @Override
    public void retryJob(String topic, String id, Object content) {
        List<String> keys = Lists.newArrayList();
        keys.add(RedisKeyUtil.getDelayQueueTableKey());
        keys.add(RedisKeyUtil.getTopicListKey(topic));
        DefaultRedisScript redisScript = new DefaultRedisScript<>();
        redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/retryJob.lua")));
        this.execute(redisScript.getScriptAsString(), keys, Arrays.asList(RedisKeyUtil.getTopicId(topic, id), JSONObject.toJSONString(content)));
    }

    @Override
    public void deleteJob(String topic, String id) {
        List<String> keys = Lists.newArrayList();
        keys.add(RedisKeyUtil.getDelayQueueTableKey());
        keys.add(RedisKeyUtil.getBucketKey());
        DefaultRedisScript redisScript = new DefaultRedisScript<>();
        redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/deleteJob.lua")));
        this.execute(redisScript.getScriptAsString(), keys, Collections.singletonList(RedisKeyUtil.getTopicId(topic, id)));
    }

    @Override
    public long moveAndRtTopScore() {
        List<String> keys = new ArrayList<>(2);
        //移动到的待消费列表key  这里是前缀: 在lua脚本会解析真正的topic
        keys.add(RedisKeyUtil.getTopicListPreKey());
        //被移动的zset
        keys.add(RedisKeyUtil.getBucketKey());
        DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
        redisScript.setResultType(String.class);
        redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/moveAndRtTopScore.lua")));
        String newTime = (String) this.execute(redisScript.getScriptAsString(), keys, Collections.singletonList(String.valueOf(System.currentTimeMillis())));
        //logger.info("执行一次移动操作用时:{} ",System.currentTimeMillis()-before);
        if (StringUtils.isEmpty(newTime)) return Long.MAX_VALUE;
        return Long.parseLong(newTime);
    }


    @Override
    public List<String> lrangeAndLTrim(String topic, int maxGet) throws IOException {
        //lua 是以0开始为
        maxGet = maxGet - 1;
        List<String> keys = new ArrayList<>(1);
        //移动到的待消费列表key
        keys.add(RedisKeyUtil.getTopicListKey(topic));
        DefaultRedisScript<Object> redisScript = new DefaultRedisScript<>();
        redisScript.setResultType(Object.class);
        redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/lrangAndLTrim.lua")));

        ResourceScriptSource resourceScriptSource = new ResourceScriptSource(new ClassPathResource("lua/lrangAndLTrim.lua"));
        resourceScriptSource.getScriptAsString();
        Object values;
        try {
            values = this.execute(redisScript.getScriptAsString(), keys, Collections.singletonList(String.valueOf(maxGet)));
        } catch (RedisSystemException e) {
            //redistemplate 有个bug  没有获取到数据的时候报空指针
            if (e.getCause() instanceof NullPointerException) {
                return null;
            } else {
                logger.error("lrangeAndLTrim 操作异常;{}", ExceptionUtil.getStackTrace(e));
                throw e;
            }
        }
        List<String> list = (List<String>) values;
        return list;
    }


    /**
     * 执行lua脚本公共方法
     * @param script 执行脚本
     * @param keys   key集合
     * @param args   参数结合
     * @return object
     */
    private Object execute(String script, List<String> keys, List<String> args) {
        return this.redisTemplate.execute((RedisCallback<Object>) redisConnection -> {
            Object nativeConnection = redisConnection.getNativeConnection();
            return ((JedisCluster) nativeConnection).eval(script, keys, args);
        });
    }

}
