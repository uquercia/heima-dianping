package com.hmdp.interceptor;

import com.hmdp.Enum.ErrorEnum.CommonErrorEnum;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Objects;

@Slf4j
@Component
public class LoginInterceptor implements HandlerInterceptor {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
    }
    //只需要校验UserHolder里面有没有东西，假如没东西那就是没登录，

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        UserDTO user = UserHolder.getUser();
        if (Objects.isNull(user)) {
            response.setStatus((int)CommonErrorEnum.UNAUTHORIZED.getCode());
//            log.debug("通过拦截器失败");
            return false;
        }else {
//            log.debug("通过拦截器登陆成功");
            return true;
        }
    }
}
