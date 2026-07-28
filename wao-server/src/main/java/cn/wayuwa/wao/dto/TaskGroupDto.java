package cn.wayuwa.wao.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 项目内的任务分组（分组视图用）。
 *
 * <p>一个项目常常同时压着好几个 domain 的事，状态看板按「待办 / 进行中」切分帮不上忙 ——
 * 分组按人自己的说法切（「证件」「装修」「上线前」），组内只关心做没做完。
 *
 * @param id   分组标识，项目内唯一
 * @param name 分组名称，由用户自己写
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskGroupDto(String id, String name) {
}
