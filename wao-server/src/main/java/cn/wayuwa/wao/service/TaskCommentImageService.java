package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.dto.ImageItemDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Images are private from upload onward and inherit the current task's visibility. */
@Service
public class TaskCommentImageService {
    private final JdbcTemplate jdbc;
    private final TaskService tasks;
    private final ImageService images;
    private final NoteAssetPrivacyService privacy;
    public TaskCommentImageService(JdbcTemplate jdbc, TaskService tasks, ImageService images, NoteAssetPrivacyService privacy) {
        this.jdbc = jdbc; this.tasks = tasks; this.images = images; this.privacy = privacy;
    }
    @Transactional
    public ImageItemDto upload(String taskId, String memberId, MultipartFile file) {
        if (memberId == null) throw new NotFoundException("任务不存在或无权访问");
        var task = tasks.get(taskId, memberId);
        var image = images.upload(file, "任务评论", task.project());
        privacy.privateUpload("images", image.id());
        jdbc.update("INSERT INTO task_comment_image(image_id,task_id) VALUES (?,?)", image.id(), taskId);
        return image;
    }
    public void requireAccessIfCommentImage(String imageId, String memberId) {
        var ids = jdbc.queryForList("SELECT task_id FROM task_comment_image WHERE image_id=?", String.class, imageId);
        if (ids.isEmpty()) return;
        if (memberId == null) throw new NotFoundException("资源不存在或无权访问");
        tasks.get(ids.get(0), memberId);
    }
}
