package com.tencent.wxcloudrun.dao;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Map;

public interface ProfileMapper {
  @Select("SELECT 1")
  int ping();

  @Insert("""
      INSERT INTO app_user (id, appid, openid) VALUES (#{id}, #{appid}, #{openid})
      ON DUPLICATE KEY UPDATE openid = #{openid}
      """)
  void ensureUser(@Param("id") String id, @Param("appid") String appid, @Param("openid") String openid);

  @Select("SELECT id, created_at FROM app_user WHERE appid = #{appid} AND openid = #{openid}")
  UserRow findUser(@Param("appid") String appid, @Param("openid") String openid);

  @Select("""
      SELECT c.name, c.phone, c.company, c.position, c.address, c.email, c.wechat, c.intro, c.saved_at
      FROM user_card c JOIN app_user u ON c.user_id = u.id
      WHERE u.appid = #{appid} AND u.openid = #{openid}
      """)
  CardRow findCard(@Param("appid") String appid, @Param("openid") String openid);

  @Insert("""
      INSERT INTO user_card (user_id, name, phone, company, position, address, email, wechat, intro, saved_at)
      VALUES (#{userId}, #{v.name}, #{v.phone}, #{v.company}, #{v.position}, #{v.address},
              #{v.email}, #{v.wechat}, #{v.intro}, CURRENT_TIMESTAMP(6))
      ON DUPLICATE KEY UPDATE name = #{v.name}, phone = #{v.phone}, company = #{v.company},
          position = #{v.position}, address = #{v.address}, email = #{v.email},
          wechat = #{v.wechat}, intro = #{v.intro}, saved_at = CURRENT_TIMESTAMP(6)
      """)
  void saveCard(@Param("userId") String userId, @Param("v") Map<String, String> values);

  record UserRow(String id, LocalDateTime createdAt) {}
  record CardRow(String name, String phone, String company, String position, String address,
                 String email, String wechat, String intro, LocalDateTime savedAt) {}
}
