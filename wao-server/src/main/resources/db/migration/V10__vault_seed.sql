-- 密钥库域种子（与前端 fixtures 一致）。演示条目均为虚构元数据，不含真实密码，secret 留空。

INSERT INTO vault_group (id, name, ord) VALUES
('vg1', '家庭网络', 1),
('vg2', '生活缴费', 2),
('vg3', '订阅服务', 3);

INSERT INTO vault_item (id, group_id, name, icon, user_name, site, note, updated_label, ord) VALUES
('vi1', 'vg1', '家庭 Wi-Fi', 'ri-wifi-line', 'wayuwa-home', '192.168.1.1', '路由器管理密码单独存放；访客网络每季度更换。', '6月12日', 1),
('vi2', 'vg1', 'NAS 管理后台', 'ri-hard-drive-2-line', 'admin@nas.local', 'nas.local:5001', '开启了两步验证，恢复代码在保险柜。', '5月30日', 2),
('vi3', 'vg2', '水电煤缴费账户', 'ri-flashlight-line', '138****0000', '95598.example', '', '4月18日', 1),
('vi4', 'vg3', '流媒体家庭组', 'ri-movie-2-line', 'family@wayuwa.example', 'tv.example.com', '家庭组 4 人位，娃弟管理续费。', '7月2日', 1),
('vi5', 'vg3', '云相册会员', 'ri-image-line', 'wd@wayuwa.example', 'photos.example.com', '', '6月20日', 2);
