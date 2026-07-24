package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 组织成员表实体。 */
@TableName("member")
public class MemberEntity {

    @TableId
    private String id;
    private String name;
    private String initial;
    private String color;
    private String role;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getInitial() { return initial; }
    public void setInitial(String initial) { this.initial = initial; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
}
