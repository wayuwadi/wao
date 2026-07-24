package cn.wayuwa.wao.service;

import java.util.List;

import cn.wayuwa.wao.dto.AreaDto;
import cn.wayuwa.wao.mapper.AreaMapper;
import org.springframework.stereotype.Service;

/** 人生领域读取服务。 */
@Service
public class AreaService {

    private final AreaMapper areaMapper;

    public AreaService(AreaMapper areaMapper) {
        this.areaMapper = areaMapper;
    }

    /**
     * 列出全部人生领域。
     *
     * @return 领域列表
     */
    public List<AreaDto> list() {
        return areaMapper.selectList(null).stream()
                .map(e -> new AreaDto(e.getId(), e.getName(), e.getColor()))
                .toList();
    }
}
