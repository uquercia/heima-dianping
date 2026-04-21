package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.redis.RedisConstants;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private UserMapper userMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void generateTokens() throws IOException {
        List<String> tokens = new ArrayList<>(1000);
        for (int i = 0; i < 1000; i++) {
            String phone = String.format("139%08d", i);
            User user = getOrCreateUser(phone, i);
            tokens.add(createToken(user));
        }
        Path tokenFile = Paths.get(System.getProperty("user.dir"), "tokens");
        Files.write(tokenFile, tokens, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private User getOrCreateUser(String phone, int index) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        if (user != null) {
            return user;
        }
        User newUser = new User();
        newUser.setPhone(phone);
        newUser.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + index);
        newUser.setIcon("");
        newUser.setCreateTime(LocalDateTime.now());
        newUser.setUpdateTime(LocalDateTime.now());
        userMapper.insert(newUser);
        return newUser;
    }

    private String createToken(User user) {
        String token = IdUtil.fastSimpleUUID();
        UserDTO userDTO = BeanUtil.toBean(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true));
        Map<String, String> stringMap = new HashMap<>();
        userMap.forEach((key, value) -> stringMap.put(key, String.valueOf(value)));
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, stringMap);
        return token;
    }
}
