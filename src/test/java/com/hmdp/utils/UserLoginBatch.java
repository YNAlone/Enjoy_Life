package com.hmdp.utils;

import cn.hutool.json.JSONObject;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

@RunWith(SpringRunner.class)
@SpringBootTest
public class UserLoginBatch {
    @Autowired
    private IUserService userService;

    @Test
    public void function(){
        String loginUrl = "http://localhost:8080/api/user/login";
        String tokenFilePath = "token.txt";
        try{
            HttpClient httpClient = HttpClients.createDefault();
            BufferedWriter br = new BufferedWriter(new FileWriter(tokenFilePath));
//            从数据库中获取用户手机号

            List<User> users = userService.list();
            for(User user : users){
                String phoneNumber = user.getPhone();
//                构建登录请求
                HttpPost httpPost = new HttpPost(loginUrl);
                JSONObject jsonRequest = new JSONObject();
                jsonRequest.put("phone", phoneNumber);
                StringEntity requestEntity = new StringEntity(
                        jsonRequest.toString(),
                        ContentType.APPLICATION_JSON
                );

                httpPost.setEntity(requestEntity);
//                发送登录请求
                HttpResponse response = httpClient.execute(httpPost);

//                处理登录请求
                if(response.getStatusLine().getStatusCode() == 200){
                    HttpEntity entity = response.getEntity();
                    String responseString = EntityUtils.toString(entity);
                    System.out.println(responseString);
//                    解析响应，获取token假设相应是JSON形式
//                    选择JSON库解析
                    String token = parseTokenFromJson(responseString);
                    System.out.println("手机号" + phoneNumber + "登录成功，token:" + token);
//                    写入txt
                    br.write(token);
                    br.newLine();
                }
                else {
                    System.out.println("手机号" + phoneNumber + "登录失败");

                }
            }
            br.close();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String parseTokenFromJson(String json) {
        try {
//            将JSON转为JSONObject
            JSONObject jsonObject = new JSONObject(json);
//            从JSONObject中获取"token"的字段值
            String token = jsonObject.getStr("data");
            return token;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}


