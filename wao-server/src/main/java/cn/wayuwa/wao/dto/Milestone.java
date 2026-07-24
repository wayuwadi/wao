package cn.wayuwa.wao.dto;

/** 项目里程碑。state：0=未来 1=进行中 2=完成。 */
public record Milestone(String name, String date, int state) {
}
