package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.dto.ImageItemDto;
import cn.wayuwa.wao.dto.TaskDto;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskCommentImageServiceTest {
    @Test void bindsPrivateUploadAndChecksCurrentTaskAccess() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:task-image;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE task_comment_image(image_id VARCHAR(64) PRIMARY KEY, task_id VARCHAR(64) NOT NULL)");
        TaskService tasks = mock(TaskService.class);
        ImageService images = mock(ImageService.class);
        NoteAssetPrivacyService privacy = mock(NoteAssetPrivacyService.class);
        TaskCommentImageService service = new TaskCommentImageService(jdbc, tasks, images, privacy);
        TaskDto task = mock(TaskDto.class);
        ImageItemDto image = mock(ImageItemDto.class);
        MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", new byte[]{1});
        when(tasks.get("task1", "owner")).thenReturn(task);
        when(task.project()).thenReturn("project1");
        when(images.upload(file, "任务评论", "project1")).thenReturn(image);
        when(image.id()).thenReturn("image1");
        service.upload("task1", "owner", file);
        verify(privacy).privateUpload("images", "image1");
        assertThat(jdbc.queryForObject("SELECT task_id FROM task_comment_image WHERE image_id='image1'", String.class)).isEqualTo("task1");
        when(tasks.get("task1", "other")).thenThrow(new NotFoundException("无权访问"));
        assertThatThrownBy(() -> service.requireAccessIfCommentImage("image1", null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.requireAccessIfCommentImage("image1", "other")).isInstanceOf(NotFoundException.class);
        service.requireAccessIfCommentImage("image1", "owner");
        verify(tasks, times(2)).get("task1", "owner");
    }
}
