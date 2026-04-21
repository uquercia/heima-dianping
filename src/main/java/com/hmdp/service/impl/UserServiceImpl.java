package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.Enum.ErrorEnum.PhoneErrorEnum;
import com.hmdp.exception.LoginException;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.redis.RedisConstants;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 服务实现类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private UserMapper userMapper;

    /**
     * 登录
     * 通过手机号和验证码进行登录验证
     *
     * 业务流程：
     * 1. 校验手机号格式
     * 2. 验证验证码是否正确
     * 3. 查询或创建用户
     * 4. 生成JWT token并存入Redis
     * @param loginForm 登录表单
     * @param session   会话
     * @return 字符串
     */
    @Override
    public String login(LoginFormDTO loginForm, HttpSession session) {
        //通过key phone校验code是否一致，一致则用手机号查表看有没有用户，
        // 没查到->注册，在redis中以keyvalue存入redis，下发jwt，然后登陆成功

        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            throw new LoginException(PhoneErrorEnum.PHONE_INVALID);
        }
        //验证码是否相等 首先code判空或者不相等直接报错
        String redisCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if (redisCode == null || !redisCode.equals(loginForm.getCode())) {
            throw new LoginException(
                redisCode == null ? PhoneErrorEnum.CODE_EMPTY : PhoneErrorEnum.CODE_INVALID
            );
        }
        //通过phone查表看有没有用户
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(StringUtils.hasText(phone),User::getPhone, phone));
        if (Objects.isNull(user)){
            //创建用户
            user = new User();
            user.setPhone(loginForm.getPhone());
            user.setCreateTime(LocalDateTime.now());
            userMapper.insert(user);
        }
        //生成token (只有token不合法才会到登陆页面)
        // 2.保存用户信息到 redis中
        // 随机生成token，作为登录令牌
        String token = IdUtil.fastSimpleUUID();
        //对象拷贝给userDTO
        UserDTO userDTO = BeanUtil.toBean(user, UserDTO.class);
        //存到redisHash
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                    CopyOptions.create()
                    .setIgnoreNullValue(true));

        //手动转为Map<String,String>(用hutool来转的话会有bug 比如说存入的数据会有""1010"")
        Map<String,String> stringMap=new HashMap<>();
        userMap.forEach(
            (key, value) -> {
                stringMap.put(key, value.toString());
            }
        );

        String tokenKey = RedisConstants.LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, stringMap);
        return token;
    }


    /**
     * 发送代码
     *
     * @param phone   电话
     * @param session 会话
     */
    @Override
    public void sendCode(String phone, HttpSession session) {
        //校验手机 通过发验证码 tokenKey :phone value:code 存redis 发验证码
        if (RegexUtils.isPhoneInvalid(phone)) {
            throw new LoginException(PhoneErrorEnum.PHONE_INVALID);
        }
        //随机生成验证码
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY+phone, code);
        log.debug("手机号为：{} ,验证码: {}", phone, code);
    }
}
