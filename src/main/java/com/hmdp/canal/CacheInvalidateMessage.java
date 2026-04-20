package com.hmdp.canal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
//消息体
public class CacheInvalidateMessage {
    private String tableName;     //“tb_shop” / "tb_shop_type"
    private Long id;              //变更的记录ID ， null代表全部失效
    private String eventType;     //"UPDATE" / "DELETE"  /"INSERT"

}
