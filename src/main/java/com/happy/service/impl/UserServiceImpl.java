package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
//        1.校验手机号
        if(RegexUtils.isPhoneInvalid( phone)){
            //        2.手机号错误返回错误信息
            return Result.fail("手机号格式错误") ;
        }

//        3.手机号正确生成验证码
        String code = RandomUtil.randomNumbers(6);
//        4.保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
//        5.发送验证码
        log.debug("发送验证码成功，验证码：{}",code);
        return Result.ok("发送验证码成功");
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
//        1.校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid( loginForm.getPhone())){
            return Result.fail("手机号格式错误") ;
        }
//        2.从redis获取验证码并校验
//        Object cacheCode = session.getAttribute("code");
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(cacheCode == null || !cacheCode.toString().equals(code)){
            //        3.不一致,返回错误信息
            return Result.fail("验证码错误");
        }
//        4.一致,根据手机号查询用户
        User user = query().eq("phone",phone).one();

//        5.用户是否存在
        if(user == null){
//        6.不存在，创建新用户并保存到数据库
            user = createUserWithPhone(loginForm.getPhone());
        }
//        7 保存用户信息到redis中

//        7.1 随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
//        7.2 将User对象转为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String,Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue( true)
                        .setFieldValueEditor( (fieldName,fieldValue) -> fieldValue.toString()));
//        7.3 存储
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token,userMap);
//        7.4 设置token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token,LOGIN_USER_TTL, TimeUnit.MINUTES);
//        7.存在，保存用户到session
//        session.setAttribute("user", BeanUtil.copyProperties(user,UserDTO.class));

//        8. 返回token
        return Result.ok(token);
    }


    @Override
    public Result sign() {
//        1.获取当前用户信息
        Long userId = UserHolder.getUser().getId();
//        2.获取当前日期
        LocalDateTime now = LocalDateTime.now();
//        3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
//        4.获取今天是本月第几天
        int dayOffMonth = now.getDayOfMonth() - 1;
//        5.写入Redis
        stringRedisTemplate.opsForValue().setBit(key,dayOffMonth,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
//        1.获取当前用户信息
        Long userId = UserHolder.getUser().getId();
//        2.获取当前日期
        LocalDateTime now = LocalDateTime.now();
//        3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
//        4.获取本月截止到今天的所有签到记录
        int dayOfMonth = now.getDayOfMonth();
//        5.获取本月截止今天之前的所有签到记录
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );
        if(result == null || result.isEmpty()) {
//            如果没有签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num == null || num == 0L) {
            return Result.ok(0);
        }
//        6.循环遍历
        int tol = 0;
        while(true) {
            if((num & 1) == 1) {
                tol++;
            }
            else {
                break;
            }
            num >>>= 1;
            }
        return Result.ok(tol);
    }

    private User createUserWithPhone(String phone) {
        User user = new User()
                .setPhone(phone)
                .setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
//        8.保存用户到数据库
        save(user);             //mybatis_plus中的save方法
        return user;
    }
}
