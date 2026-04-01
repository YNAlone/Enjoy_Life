package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.datatype.jsr310.ser.ZoneIdSerializer;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            // 查询用户
            this.queryBlogUser(blog);
            // 查询是否点赞
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
//        1.查询blog
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("博客不存在");
        }
//        2.查询blog有关的用户
        queryBlogUser(blog);
//        3.查询当前用户是否点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {
//        1.获取当前用户
        Long userId = UserHolder.getUser().getId();
//        2.判断当前用户是否点赞
        String key = "blog:liked:" + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null){
        //        3.如果未点赞，可以点赞
//                  3.1数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if(isSuccess){
//                  3.2 保存用户到Redis的set集合  zadd key value score
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }
        else{
        //        4.已经点赞过，取消点赞
        //        4.1数据库点赞数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        //        4.2 从Redis的set集合移除用户
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        //        1.查询top5的点赞用户
//          2.查看是否有用户点赞，如果没有，则返回空列表

        Set<String> top5 =  stringRedisTemplate.opsForZSet().range(key,0,4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
//        2.解析用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",",ids);
//        3.根据用户id查询对应用户
        List<UserDTO> userDTOS = userService.query()
                .in("id",ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user,UserDTO.class))
                .collect(Collectors.toList());
//        4.返回
        return Result.ok(userDTOS);

    }

    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        if (blog.getShopId() == null) {
            return Result.fail("请选择关联商户");
        }
        // 2.保存探店笔记
        boolean isSuccess = save(blog);
        if(!isSuccess) {
            return Result.fail("笔记保存失败");
        }
//        3.查询作者的粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
//        4.推送笔记给粉丝
        for(Follow follow : follows){
            Long userId = follow.getUserId();
            String key = FEED_KEY + userId;
            // 4.1 推送笔记给粉丝
            stringRedisTemplate.opsForZSet().add(key ,blog.getId().toString(),System.currentTimeMillis());
        }
        // 5.返回id
        return Result.ok(blog.getId());

    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
//        1.获取当前用户
        Long userId = UserHolder.getUser().getId();
//        2.查询收件箱
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
//        3.非空判断
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
//        4.解析数据 blogId 、score 、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for(ZSetOperations.TypedTuple<String> tuple : typedTuples) {
//            4.1获取id
            String idStr = tuple.getValue();
            ids.add(Long.valueOf(idStr));
//            4.2获取时间戳
            long time  = tuple.getScore().longValue();
            if(time == minTime){
                os++;
            }
            else{
                minTime = time;
                os = 1;
            }
        }
        //                4.查询blog，封装返回
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id",ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            //        2.查询blog有关的用户
            queryBlogUser(blog);
//        3.查询当前用户是否点赞
            isBlogLiked(blog);
        }
//        5.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }

    private void isBlogLiked (Blog blog) {
//        1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if(user == null) {
//            用户未登录，无需查询是否点赞
            return ;
        }
        Long userId = user.getId();
//        2.判断登录用户是否点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }


}
