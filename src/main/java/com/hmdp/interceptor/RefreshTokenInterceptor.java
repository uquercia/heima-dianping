package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.redis.RedisConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

@Slf4j
@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //拦截之前处理
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取token
        String token = request.getHeader("authorization");
        //校验token是否为空 为空就说明没登陆过直接放 给下一层去拦截
        //不为空就继续 判断对象是否合法
        if (!StringUtils.hasText(token)) {
            log.debug("用户没有token");
            return true;
        }
        //下面是判断是否是用户伪造的token token是否能查到真正的用户
        //从redis通过key获取hash对象
        String key = RedisConstants.LOGIN_USER_KEY+token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        //用户没查到 拦截
        //不存上下文
        if (userMap == null || userMap.isEmpty()) {
            log.debug("用户的token是伪造的");
            return true;
        }
        //查到获取用户信息，从hash数据转化为实体类型
        //存上下文
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //把用户的信息存入上下文UserHolder
        UserHolder.saveUser(userDTO);
        //放行
        log.debug("把用户：{}存入了上下文，通过拦截器refresh",userDTO);
        return true;
    }
    //程序结束前处理
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
//        移除用户上下文对象UserHolder
        UserHolder.removeUser();
    }
}
