package cn.wayuwa.wao.service;

import java.util.List;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.domain.OrgEntity;
import cn.wayuwa.wao.dto.MemberDto;
import cn.wayuwa.wao.dto.OrgDto;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.mapper.OrgMapper;
import org.springframework.stereotype.Service;

/** 组织与成员读取服务。 */
@Service
public class MemberService {

    private final MemberMapper memberMapper;
    private final OrgMapper orgMapper;

    public MemberService(MemberMapper memberMapper, OrgMapper orgMapper) {
        this.memberMapper = memberMapper;
        this.orgMapper = orgMapper;
    }

    /**
     * 列出组织内全部成员。
     *
     * @return 成员列表
     */
    public List<MemberDto> list() {
        return memberMapper.selectList(null).stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * 返回当前（唯一）组织信息。
     *
     * @return 组织
     * @throws NotFoundException 组织未初始化时
     */
    public OrgDto org() {
        OrgEntity e = orgMapper.selectList(null).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("组织不存在"));
        return new OrgDto(e.getId(), e.getName(), Math.toIntExact(memberMapper.selectCount(null)));
    }

    private MemberDto toDto(MemberEntity e) {
        return new MemberDto(e.getId(), e.getName(), e.getInitial(), e.getColor(), e.getRole());
    }
}
