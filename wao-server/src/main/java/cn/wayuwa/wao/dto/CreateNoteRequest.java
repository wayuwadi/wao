package cn.wayuwa.wao.dto;

import java.util.List;

/**
 * 新建小记请求：不指定知识库，一律落到小记知识库。
 *
 * @param title  标题，留空时由正文首行推导
 * @param blocks 正文块
 */
public record CreateNoteRequest(String title, List<DocBlockDto> blocks, RichDocContentDto content,
        List<String> tags, Boolean autoTitle, Boolean autoImage, Boolean autoPolish, List<String> sourceInputIds,
        Boolean createOnly, Boolean systemGenerated) {
    /** 兼容普通创建和幂等创建；省略来源时仍是个人记录。 */
    public CreateNoteRequest(String title,List<DocBlockDto> blocks,RichDocContentDto content,List<String> tags,Boolean autoTitle,Boolean autoImage,Boolean autoPolish,List<String> sourceInputIds,Boolean createOnly){this(title,blocks,content,tags,autoTitle,autoImage,autoPolish,sourceInputIds,createOnly,null);}
    /** 保留旧客户端与来源留存调用；createOnly 只在明确首次创建时启用。 */
    public CreateNoteRequest(String title,List<DocBlockDto> blocks,RichDocContentDto content,List<String> tags,Boolean autoTitle,Boolean autoImage,Boolean autoPolish,List<String> sourceInputIds){this(title,blocks,content,tags,autoTitle,autoImage,autoPolish,sourceInputIds,null);}
    public CreateNoteRequest(String title,List<DocBlockDto> blocks,RichDocContentDto content,List<String> tags,Boolean autoTitle,Boolean autoImage,Boolean autoPolish){this(title,blocks,content,tags,autoTitle,autoImage,autoPolish,null);}
    public CreateNoteRequest(String title, List<DocBlockDto> blocks, RichDocContentDto content, List<String> tags) {
        this(title, blocks, content, tags, null, null, null);
    }
    public CreateNoteRequest(String title, List<DocBlockDto> blocks, RichDocContentDto content) {
        this(title, blocks, content, null, null, null, null);
    }
    public CreateNoteRequest(String title, List<DocBlockDto> blocks) {
        this(title, blocks, null, null, null, null, null);
    }
}
