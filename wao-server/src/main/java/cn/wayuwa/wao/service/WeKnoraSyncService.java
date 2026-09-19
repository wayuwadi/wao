package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 持久化增量同步队列；MySQL 连接级命名锁跨节点串行，重启后先核对再恢复。 */
@Service
public class WeKnoraSyncService {
    public static final String HANDLER="weknora-content-sync", JOB_ID="weknora-content-sync";
    static final Set<String> KNOWLEDGE_MODULES=Set.of("docs","memory","personal-memory","project-memory");
    private static final String LIVE_SETTING="weknora-sync-live-enabled", LIVE_CHECK="weknora-sync-live-check";
    public record Settings(String kbId,String apiKey,List<String> modules,int batchLimit,boolean syncDeletes,
                           boolean enabled,String time,int revision,Boolean liveSync) {
        public Settings(String kbId,String apiKey,List<String> modules,int batchLimit,boolean syncDeletes,
                        boolean enabled,String time,int revision) {
            this(kbId,apiKey,modules,batchLimit,syncDeletes,enabled,time,revision,null);
        }
    }
    private final JdbcTemplate db;
    private final DataSource dataSource;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final VaultCryptoService crypto;
    private final WeKnoraSourceService sources;
    private final WeKnoraClient client;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"weknora-sync");t.setDaemon(true);return t; });
    private final AtomicBoolean running=new AtomicBoolean();
    public WeKnoraSyncService(JdbcTemplate db,DataSource dataSource,TransactionTemplate transactions,ObjectMapper json,
            VaultCryptoService crypto,WeKnoraSourceService sources,WeKnoraClient client) {
        this.db=db;this.dataSource=dataSource;this.transactions=transactions;this.json=json;
        this.crypto=crypto;this.sources=sources;this.client=client;
    }
    /** 返回脱敏设置、真实来源计数与最近运行记录，不回传 API Key。 */
    public Map<String,Object> overview(String owner) {
        Map<String,Object> config=config();
        Map<String,Object> view=new LinkedHashMap<>();
        if (config!=null) {
            view.put("kbId",config.get("kb_id"));view.put("modules",modules(config));view.put("batchLimit",config.get("batch_limit"));
            view.put("syncDeletes",flag(config.get("sync_deletes")));view.put("revision",config.get("revision"));view.put("hasKey",true);
            view.put("liveSync",liveSyncEnabled());
        }
        if(config!=null)view.put("enabled",false);
        var job=db.queryForList("SELECT status,cron_expr,next_fire_at,schedule_label FROM automation_job WHERE id=?",JOB_ID);
        if (!job.isEmpty()) {
            var j=job.get(0);String[] cron=Objects.toString(j.get("cron_expr"),"0 0 2 * * *").split(" ");
            view.put("enabled",!"已暂停".equals(j.get("status")));
            boolean daily=cron.length==6 && cron[1].matches("[0-9]{1,2}") && cron[2].matches("[0-9]{1,2}") && cron[3].equals("*") && cron[4].equals("*") && cron[5].equals("*");
            view.put("time",daily?String.format("%02d:%02d",Integer.parseInt(cron[2]),Integer.parseInt(cron[1])):"02:00");
            view.put("customSchedule",!daily);
            view.put("nextFireAt",j.get("next_fire_at"));
        }
        return Map.of("ownerId",owner,"configured",config!=null,"settings",view,"catalog",sources.catalog(owner),"runs",runs(),
            "items",db.queryForList("SELECT source_key,module_id,source_title,source_url,remote_id,status,parse_status,summary_status,error_message,updated_at FROM weknora_sync_item ORDER BY updated_at DESC LIMIT 100"),
            "totals",db.queryForMap("SELECT COUNT(*) AS tracked,COALESCE(SUM(status='synced'),0) AS transferred,COALESCE(SUM(status='failed' OR status='uncertain'),0) AS failed FROM weknora_sync_item"));
    }
    /** 轮询只返回运行信息，前端不使用它重置正在编辑的表单。 */
    public Map<String,Object> progress(String owner) {
        return Map.of("runs",runs(),"items",db.queryForList("SELECT source_key,module_id,source_title,source_url,remote_id,status,parse_status,summary_status,error_message,updated_at FROM weknora_sync_item ORDER BY updated_at DESC LIMIT 100"));
    }
    /** 源链接展示当前源内容，权限与同步配置一致；已删除来源返回 404。 */
    public WeKnoraSourceService.Source source(String owner,String key) {
        Map<String,Object> c=config();
        if(c==null)throw new NotFoundException("尚未配置同步");
        if(key.startsWith("docs:") && modules(c).contains("docs")) {
            String[] parts=key.split(":",3);
            if(parts.length==3)return sources.documents(owner,List.of(parts[1])).stream().filter(s->s.key().equals(key)).findFirst()
                .orElseThrow(()->new NotFoundException("来源已删除或不可访问"));
        }
        return sources.snapshot(owner,modules(c)).stream().filter(s->s.key().equals(key)).findFirst()
            .orElseThrow(()->new NotFoundException("来源已删除或不在当前同步范围内"));
    }
    public record DocumentState(String id,String state,String message,String sourceUrl,String knowledgeUrl) { }
    /** 批量核对当前服务器正文的全部分段；查询者和配置者必须都能访问该文档。 */
    public List<DocumentState> documentStates(String owner,List<String> ids) {
        var visible=sources.documents(owner,ids);
        var c=config();
        Map<String,List<WeKnoraSourceService.Source>> grouped=new LinkedHashMap<>();
        visible.forEach(s->grouped.computeIfAbsent(s.key().split(":",3)[1],key->new ArrayList<>()).add(s));
        if(c==null || !modules(c).contains("docs"))return grouped.keySet().stream().map(id->new DocumentState(id,
            c==null?"unconfigured":"excluded",c==null?"尚未配置 WeKnora 同步":"知识库和小记未纳入同步","","")).toList();
        String configuredOwner=(String)c.get("owner_member_id");
        Set<String> allowed=new HashSet<>();
        (owner.equals(configuredOwner)?visible:sources.documents(configuredOwner,ids)).forEach(s->allowed.add(s.key()));
        Map<String,Map<String,Object>> tracked=new HashMap<>();
        if(!visible.isEmpty()) {
            var keys=visible.stream().map(WeKnoraSourceService.Source::key).toList();
            db.queryForList("SELECT source_key,content_hash,desired_hash,status,parse_status,summary_status FROM weknora_sync_item WHERE source_key IN ("+
                String.join(",",Collections.nCopies(keys.size(),"?"))+")",keys.toArray()).forEach(i->tracked.put((String)i.get("source_key"),i));
        }
        boolean live=liveSyncEnabled();
        List<DocumentState> result=new ArrayList<>();
        for(var entry:grouped.entrySet()) {
            var parts=entry.getValue();
            if(parts.stream().anyMatch(s->!allowed.contains(s.key()))) {
                result.add(new DocumentState(entry.getKey(),"excluded","不在同步配置者的可访问范围","",""));continue;
            }
            String state=documentState(parts,tracked);
            String message=switch(state) {
                case "failed" -> "同步或文档处理失败，可在内容同步页查看并重试";
                case "processing" -> "已传输，WeKnora 正在处理文档";
                case "processed" -> "文档解析与摘要已完成；Wiki 与问答请在 WeKnora 核对";
                default -> live?"服务器内容待同步，通常在下一分钟检查后处理":"服务器内容待同步，及时同步已暂停";
            };
            result.add(new DocumentState(entry.getKey(),state,message,parts.get(0).url(),"/weknora/platform/knowledge-bases/"+c.get("kb_id")));
        }
        return result;
    }
    static String documentState(List<WeKnoraSourceService.Source> parts,Map<String,Map<String,Object>> tracked) {
        boolean pending=false,processing=false,failed=false;
        for(var s:parts) {
            var item=tracked.get(s.key());
            if(item==null) {pending=true;continue;}
            boolean current=s.hash().equals(item.get("content_hash"));
            if((current || s.hash().equals(item.get("desired_hash"))) &&
                (Set.of("failed","uncertain").contains(item.get("status")) || current &&
                    ("failed".equals(item.get("parse_status")) || "failed".equals(item.get("summary_status")))))failed=true;
            else if(!current)pending=true;
            else if(!"synced".equals(item.get("status")) || !"completed".equals(item.get("parse_status")) || !"completed".equals(item.get("summary_status")))processing=true;
        }
        return failed?"failed":pending?"pending":processing?"processing":"processed";
    }
    private List<Map<String,Object>> runs() { return db.queryForList("SELECT id,trigger_type,status,created_at,started_at,finished_at,uploaded,skipped,removed,remaining,failed,message FROM weknora_sync_run ORDER BY created_at DESC LIMIT 20"); }
    /** 验证并保存设置，已有同步记录时禁止改绑；CAS 保护其他页面的编辑。 */
    public void save(String owner,Settings s) {
        validate(s);
        transactions.executeWithoutResult(tx -> {
            // Serializes initial configuration too, including an empty configuration table.
            db.queryForList("SELECT cfg_key FROM system_setting WHERE cfg_key='weknora-sync-lock' FOR UPDATE");
            db.queryForList("SELECT id FROM weknora_sync_config WHERE id='default' FOR UPDATE");
            Map<String,Object> old=config();
            if (active()!=null) throw new ConflictException("同步正在执行，请完成后再修改设置");
            if (old!=null && ((Number)old.get("revision")).intValue()!=s.revision) throw new ConflictException("配置已变化，请刷新后合并修改");
            if (old!=null && !Objects.equals(old.get("kb_id"),s.kbId) && db.queryForObject("SELECT COUNT(*) FROM weknora_sync_item",Long.class)>0)
                throw new ConflictException("已有同步副本，不能直接改绑目标知识库");
            String key=s.apiKey==null || s.apiKey.isBlank() ? (old==null?"":connection(old).key()) : s.apiKey.trim();
            if (key.isBlank()) throw new IllegalArgumentException("请配置 WeKnora 的专用 API Key");
            client.check(new WeKnoraClient.Connection(s.kbId,key));
            var encrypted=crypto.encrypt(key);
            if (old==null) db.update("INSERT INTO weknora_sync_config(id,owner_member_id,kb_id,api_key_cipher,api_key_nonce,modules_json,batch_limit,sync_deletes) VALUES('default',?,?,?,?,?,?,?)",owner,s.kbId,encrypted.ciphertext(),encrypted.nonce(),write(s.modules),s.batchLimit,s.syncDeletes);
            else db.update("UPDATE weknora_sync_config SET kb_id=?,api_key_cipher=?,api_key_nonce=?,modules_json=?,batch_limit=?,sync_deletes=?,revision=revision+1,updated_at=NOW(3) WHERE id='default'",s.kbId,encrypted.ciphertext(),encrypted.nonce(),write(s.modules),s.batchLimit,s.syncDeletes);
            if(s.liveSync!=null) setting(LIVE_SETTING,s.liveSync.toString());
            LocalTime time=LocalTime.parse(s.time);String cron="0 "+time.getMinute()+" "+time.getHour()+" * * *";
            LocalDateTime now=LocalDateTime.now(ZoneId.of("Asia/Shanghai"));LocalDateTime next=now.toLocalDate().atTime(time);if(!next.isAfter(now))next=next.plusDays(1);
            db.update("INSERT INTO automation_job(id,name,icon,schedule_label,cron_expr,status,last_run_label,description,source,target,steps_json,next_fire_at,ord,handler) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE schedule_label=VALUES(schedule_label),cron_expr=VALUES(cron_expr),status=VALUES(status),next_fire_at=VALUES(next_fire_at),handler=VALUES(handler)",
                JOB_ID,"WAO 内容同步至 WeKnora","ri-book-open-line","每天 "+s.time+"（北京时间）",cron,s.enabled?"正常":"已暂停","尚未运行","增量同步，详细进度在 WeKnora 内容同步页面","WAO","WeKnora",write(List.of("读取所选范围","核对并同步变化内容","记录传输结果")),next,999,HANDLER);
        });
    }
    /** 手动或定时入队；连续点击复用同一待执行任务，不阻塞 HTTP。 */
    public String enqueue(String trigger,String owner) {
        return transactions.execute(tx -> {
            var rows=db.queryForList("SELECT id FROM weknora_sync_config WHERE id='default' FOR UPDATE");
            if(rows.isEmpty())throw new IllegalArgumentException("请先保存同步配置");
            String existing=active();if(existing!=null)return existing;
            String id=UUID.randomUUID().toString();db.update("INSERT INTO weknora_sync_run(id,trigger_type,status) VALUES(?,?,'queued')",id,trigger);return id;
        });
    }
    /** 独立线程推进持久化队列，避免占用既有业务调度线程。 */
    @Scheduled(fixedDelay=5000,initialDelay=30000)
    public void advance() {
        if (!running.compareAndSet(false,true))return;
        executor.execute(() -> { try { work(); } finally { running.set(false); } });
    }
    private void work() {
        String runId=null;
        try(Connection lock=dataSource.getConnection();var statement=lock.createStatement()) {
            try(var result=statement.executeQuery("SELECT GET_LOCK('wao:weknora:sync',0)")) {if(!result.next()||result.getInt(1)!=1)return;}
            try {
                runId=active();
                Map<String,Object> currentConfig=config();
                if(runId==null)runId=queueKnowledgeRun(currentConfig);
                if(runId==null)return;
                db.update("UPDATE weknora_sync_run SET status='running',started_at=COALESCE(started_at,NOW(3)),message='正在核对来源与远端清单' WHERE id=?",runId);
                String trigger=db.queryForObject("SELECT trigger_type FROM weknora_sync_run WHERE id=?",String.class,runId);
                if("knowledge".equals(trigger) && !liveSyncEnabled()) {
                    db.update("UPDATE weknora_sync_run SET status='completed',finished_at=NOW(3),message='及时同步已暂停' WHERE id=?",runId);return;
                }
                currentConfig=config();
                synchronize(runId,currentConfig,lock,trigger!=null && trigger.startsWith("knowledge"));
            } finally { statement.execute("SELECT RELEASE_LOCK('wao:weknora:sync')"); }
        } catch(Exception ex) {
            if(runId!=null)db.update("UPDATE weknora_sync_run SET status='failed',finished_at=NOW(3),message=? WHERE id=?",safeError(ex),runId);
            // Failure of either source discovery or the remote service must not spin every five seconds.
            try { setting(LIVE_CHECK,Long.toString(System.currentTimeMillis()+240_000)); } catch(Exception ignored) { }
        }
    }
    private boolean liveSyncEnabled() { return !"false".equals(setting(LIVE_SETTING)); }
    private String setting(String key) {
        var values=db.queryForList("SELECT cfg_value FROM system_setting WHERE cfg_key=?",String.class,key);
        return values.isEmpty()?"":values.get(0);
    }
    private void setting(String key,String value) {
        db.update("INSERT INTO system_setting(cfg_key,cfg_value,updated_at) VALUES(?,?,NOW(3)) ON DUPLICATE KEY UPDATE cfg_value=VALUES(cfg_value),updated_at=NOW(3)",key,value);
    }
    // Called only while holding the existing cross-node named lock. The persistent check time
    // prevents another node from immediately repeating an unchanged or failed scan.
    private String queueKnowledgeRun(Map<String,Object> config) {
        if(config==null || !liveSyncEnabled())return null;
        List<String> selected=modules(config).stream().filter(KNOWLEDGE_MODULES::contains).toList();
        if(selected.isEmpty())return null;
        long now=System.currentTimeMillis(),last=0;
        try { last=Long.parseLong(setting(LIVE_CHECK)); } catch(NumberFormatException ignored) { }
        if(now-last<60_000)return null;
        setting(LIVE_CHECK,Long.toString(now));
        try {
            var snapshot=sources.snapshot((String)config.get("owner_member_id"),selected);
            var tracked=db.queryForList("SELECT source_key,module_id,content_hash,status,parse_status,summary_status FROM weknora_sync_item");
            if(!knowledgeChanged(snapshot,tracked,selected,flag(config.get("sync_deletes"))))return null;
        } catch(Exception ex) {
            db.update("INSERT INTO weknora_sync_run(id,trigger_type,status,started_at,finished_at,message) VALUES(?,'knowledge','failed',NOW(3),NOW(3),?)",
                UUID.randomUUID().toString(),safeError(ex));
            throw ex;
        }
        return enqueue("knowledge",(String)config.get("owner_member_id"));
    }
    static boolean knowledgeChanged(List<WeKnoraSourceService.Source> snapshot,List<Map<String,Object>> tracked,
                                    List<String> selected,boolean deletes) {
        Map<String,Map<String,Object>> byKey=new HashMap<>();
        tracked.stream().filter(t->selected.contains(t.get("module_id"))).forEach(t->byKey.put((String)t.get("source_key"),t));
        for(var source:snapshot) {
            var previous=byKey.remove(source.key());
            if(previous==null || !source.hash().equals(previous.get("content_hash")) || !"synced".equals(previous.get("status")))return true;
            if(Set.of("pending","processing","running","finalizing").contains(Objects.toString(previous.get("parse_status"),""))
                || Set.of("pending","processing").contains(Objects.toString(previous.get("summary_status"),"")))return true;
        }
        return deletes && !byKey.isEmpty();
    }
    private void synchronize(String runId,Map<String,Object> config,Connection lock) throws Exception {
        synchronize(runId,config,lock,false);
    }
    private void synchronize(String runId,Map<String,Object> config,Connection lock,boolean knowledgeOnly) throws Exception {
        var connection=connection(config);client.check(connection);
        var selected=knowledgeOnly?modules(config).stream().filter(KNOWLEDGE_MODULES::contains).toList():modules(config);
        if(selected.isEmpty()) { db.update("UPDATE weknora_sync_run SET status='completed',finished_at=NOW(3),message='及时同步范围已取消' WHERE id=?",runId);return; }
        List<WeKnoraSourceService.Source> snapshot=sources.snapshot((String)config.get("owner_member_id"),selected);
        var remote=client.list(connection);
        Map<String,JsonNode> byId=new HashMap<>(),byTitle=new HashMap<>();
        for(var r:remote){
            db.update("UPDATE weknora_sync_item SET parse_status=?,summary_status=? WHERE remote_id=?",stage(r,"parse_status"),stage(r,"summary_status"),r.path("id").asText());
            byId.put(r.path("id").asText(),r);String marker=marker(r.path("title").asText());
            if(!marker.isBlank() && byTitle.put(marker,r)!=null)throw new IllegalStateException("远端存在重复来源副本，请先检查后再同步");}
        Map<String,Map<String,Object>> tracked=new HashMap<>();
        db.queryForList("SELECT source_key,remote_id,content_hash,status FROM weknora_sync_item").stream()
            .filter(i->!knowledgeOnly || selected.contains(((String)i.get("source_key")).split(":",2)[0]))
            .forEach(i->tracked.put((String)i.get("source_key"),i));
        Set<String> alive=new HashSet<>();int uploaded=0,skipped=0,removed=0,remaining=0,failed=0,operations=0,deleteOperations=0;
        int limit=((Number)config.get("batch_limit")).intValue();
        // Fair retries: a failing early item must not starve every subsequent source.
        List<WeKnoraSourceService.Source> ordered=new ArrayList<>(snapshot);
        ordered.sort(Comparator.<WeKnoraSourceService.Source,Boolean>comparing(s -> !KNOWLEDGE_MODULES.contains(s.module()))
            .thenComparing(s -> tracked.containsKey(s.key()) && Set.of("failed","uncertain").contains(tracked.get(s.key()).get("status"))));
        snapshot.forEach(source -> alive.add(source.key()));
        for(var source:ordered) {
            alive.add(source.key());var item=tracked.get(source.key());
            String remoteId=item==null?null:(String)item.get("remote_id");
            if(remoteId!=null && !byId.containsKey(remoteId))remoteId=null;
            if(remoteId==null && byTitle.containsKey(marker(source.title())))remoteId=byTitle.get(marker(source.title())).path("id").asText();
            if(item!=null && source.hash().equals(item.get("content_hash")) && remoteId!=null){db.update("UPDATE weknora_sync_item SET remote_id=?,status='synced',error_message='' WHERE source_key=?",remoteId,source.key());skipped++;continue;}
            if(operations>=limit){remaining++;continue;}
            assertLock(lock);operations++;
            boolean uncertain=item!=null && Set.of("creating","uncertain").contains(item.get("status"));
            db.update("INSERT INTO weknora_sync_item(source_key,module_id,source_title,source_url,desired_hash,status,last_run_id) VALUES(?,?,?,?,?,'pending',?) ON DUPLICATE KEY UPDATE source_title=VALUES(source_title),source_url=VALUES(source_url),desired_hash=VALUES(desired_hash),last_run_id=VALUES(last_run_id),updated_at=NOW(3)",source.key(),source.module(),source.title(),source.url(),source.hash(),runId);
            try {
                if(remoteId==null && uncertain)throw new WeKnoraClient.ApiFailure("上次创建结果不确定，尚未找到远端副本；保留待核对，禁止重复创建",true);
                db.update("UPDATE weknora_sync_item SET status=? WHERE source_key=?",remoteId==null?"creating":"updating",source.key());
                JsonNode result=client.write(connection,remoteId,source);
                String id=result.path("id").asText();if(id.isBlank())throw new WeKnoraClient.ApiFailure("远端没有返回文档标识",true);
                db.update("UPDATE weknora_sync_item SET remote_id=?,content_hash=?,status='synced',parse_status=?,summary_status=?,error_message='',updated_at=NOW(3) WHERE source_key=?",id,source.hash(),stage(result,"parse_status"),stage(result,"summary_status"),source.key());uploaded++;
            } catch(Exception ex) {
                boolean ambiguous=remoteId==null && (!(ex instanceof WeKnoraClient.ApiFailure a) || a.uncertain);
                db.update("UPDATE weknora_sync_item SET status=?,error_message=?,updated_at=NOW(3) WHERE source_key=?",ambiguous?"uncertain":"failed",safeError(ex),source.key());failed++;
            }
            updateCounts(runId,uploaded,skipped,removed,remaining,failed);
        }

        if(flag(config.get("sync_deletes"))) {
            for(var entry:tracked.entrySet()) {
                if(alive.contains(entry.getKey()))continue;
                if(deleteOperations>=limit){remaining++;continue;}deleteOperations++;assertLock(lock);
                String remoteId=(String)entry.getValue().get("remote_id");
                try {
                    if(remoteId!=null)client.delete(connection,remoteId);
                    else if(Set.of("creating","uncertain").contains(entry.getValue().get("status"))) {
                        throw new IllegalStateException("未确认的创建结果需要先恢复来源并核对");
                    }
                    db.update("DELETE FROM weknora_sync_item WHERE source_key=?",entry.getKey());removed++;
                } catch(Exception ex) { failed++;db.update("UPDATE weknora_sync_item SET status=?,error_message=? WHERE source_key=?",remoteId==null?"uncertain":"failed",safeError(ex),entry.getKey()); }
            }
        }

        updateCounts(runId,uploaded,skipped,removed,remaining,failed);
        if(failed>0)setting(LIVE_CHECK,Long.toString(System.currentTimeMillis()+240_000));
        db.update("UPDATE weknora_sync_run SET status=?,finished_at=NOW(3),message=? WHERE id=?",failed>0?"partial":remaining>0?"backlog":"completed",
            "已提交 "+uploaded+"，未变化 "+skipped+"，移除 "+removed+"，待下轮 "+remaining+"；Wiki 由 WeKnora 异步处理",runId);
    }
    private static String stage(JsonNode n,String key) {String s=n.path(key).asText("");return s.matches("[a-z_]{0,32}")?s:"unknown";}
    static String marker(String title) {
        var m=java.util.regex.Pattern.compile("\\[wao-sync:([0-9a-f]{24})\\]$").matcher(title);
        return m.find()?m.group(1):"";
    }
    private void assertLock(Connection c)throws Exception {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT IS_USED_LOCK('wao:weknora:sync')=CONNECTION_ID()")){if(!r.next()||r.getInt(1)!=1)throw new IllegalStateException("同步锁已失效，已停止执行");}
    }
    private void updateCounts(String id,int u,int s,int d,int r,int f) {db.update("UPDATE weknora_sync_run SET uploaded=?,skipped=?,removed=?,remaining=?,failed=? WHERE id=?",u,s,d,r,f,id);}
    private String active() {var r=db.queryForList("SELECT id FROM weknora_sync_run WHERE status IN ('queued','running') ORDER BY created_at LIMIT 1",String.class);return r.isEmpty()?null:r.get(0);}
    private Map<String,Object> config(){var r=db.queryForList("SELECT id,owner_member_id,kb_id,api_key_cipher,api_key_nonce,modules_json,batch_limit,sync_deletes,revision FROM weknora_sync_config WHERE id='default'");return r.isEmpty()?null:r.get(0);}
    private WeKnoraClient.Connection connection(Map<String,Object> c){return new WeKnoraClient.Connection((String)c.get("kb_id"),crypto.decrypt((byte[])c.get("api_key_cipher"),(byte[])c.get("api_key_nonce")));}
    private List<String> modules(Map<String,Object> c){try{return json.readValue((String)c.get("modules_json"),new TypeReference<>(){});}catch(Exception ex){throw new IllegalStateException("同步范围配置损坏");}}
    private String write(Object v){try{return json.writeValueAsString(v);}catch(Exception ex){throw new IllegalArgumentException("同步配置无法保存");}}
    private static boolean flag(Object v){return Boolean.TRUE.equals(v) || v instanceof Number n && n.intValue()!=0;}
    static String safeError(Exception ex){if(ex instanceof WeKnoraClient.ApiFailure || ex instanceof IllegalArgumentException || ex instanceof IllegalStateException){String m=Objects.toString(ex.getMessage(),"同步失败");return m.substring(0,Math.min(240,m.length()));}return "同步失败（"+ex.getClass().getSimpleName()+"），请检查服务与数据源";}
    static void validate(Settings s){if(s==null || s.kbId==null || !s.kbId.matches("[0-9a-fA-F-]{36}"))throw new IllegalArgumentException("知识库 ID 无效");WeKnoraSourceService.validateModules(s.modules);if(s.batchLimit<1||s.batchLimit>100)throw new IllegalArgumentException("每轮上限应为 1–100 篇");if(s.time==null || !s.time.matches("(?:[01]\\d|2[0-3]):[0-5]\\d"))throw new IllegalArgumentException("请填写有效的北京时间");}
    /** 应用退出中断工作线程，连接锁释放后其他节点将核对并恢复。 */
    @PreDestroy public void close(){executor.shutdownNow();}
}
