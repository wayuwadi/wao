package cn.wayuwa.wao.service;

import cn.wayuwa.wao.mapper.NoteDiscoveryMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 显式指定既有成员的 MySQL 登记状态验收；不读取正文、不建库或删除业务数据。 */
@EnabledIfSystemProperty(named="discovery.mysql.eval",matches="true")
class NoteDiscoveryMysqlTest {
    @Test void registeredLeaseBudgetAndUniqueConstraints() throws Exception {
        String member=System.getProperty("discovery.mysql.member");assertThat(member).isNotBlank();
        String url="jdbc:mysql://"+System.getenv("MYSQL_HOST")+":"+System.getenv().getOrDefault("MYSQL_PORT","3306")+"/"+System.getenv("DB_NAME")+"?serverTimezone=Asia/Shanghai";
        var ds=new DriverManagerDataSource(url,System.getenv("DB_USER"),System.getenv("DB_PASS"));var jdbc=new JdbcTemplate(ds);var db=new NoteDiscoveryMapper(jdbc);
        assertThat(db.one("SELECT id FROM member WHERE id=?",member)).isNotNull();db.ensure(member);var prior=db.lock(member);
        assertThat(prior.get("active_job_id")).isNull();assertThat(prior.get("lease_token")).isNull();boolean enabled=NoteDiscoveryMapper.flag(prior,"enabled");
        String marker="ndt"+UUID.randomUUID().toString().replace("-","");String token1=UUID.randomUUID().toString().replace("-",""),token2=UUID.randomUUID().toString().replace("-","");
        var pool=Executors.newFixedThreadPool(2);
        try {
            jdbc.update("UPDATE note_discovery_schedule SET enabled=1 WHERE member_id=?",member);
            var start=new CountDownLatch(1);var first=pool.submit(()->{start.await();return db.claim(member,token1,120);});var second=pool.submit(()->{start.await();return db.claim(member,token2,120);});start.countDown();assertThat(first.get()+second.get()).isEqualTo(1);
            var claimed=db.lock(member);String old=NoteDiscoveryMapper.string(claimed,"lease_token");long epoch=NoteDiscoveryMapper.number(claimed,"lease_epoch");
            jdbc.update("UPDATE note_discovery_schedule SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE member_id=?",member);
            String recovered=UUID.randomUUID().toString().replace("-","");var restarted=new NoteDiscoveryMapper(new JdbcTemplate(ds));assertThat(restarted.claim(member,recovered,120)).isEqualTo(1);assertThat(db.renew(member,old,epoch,120)).isZero();assertThat(restarted.renew(member,recovered,epoch+1,120)).isEqualTo(1);
            String hash=NoteDiscoveryIndexService.hash(marker);
            String insert="INSERT INTO note_mining_job(id,member_id,source_snapshot_json,source_count,origin,enable_generation,input_fingerprint,pipeline_version,status,result_type,finished_at) VALUES(?,?,'[]',0,'AUTO',0,?,'acceptance-v1','CANCELLED','MYSQL_ACCEPTANCE',CURRENT_TIMESTAMP(6))";
            jdbc.update(insert,marker,member,hash);assertThatThrownBy(()->jdbc.update(insert,"ndt"+UUID.randomUUID().toString().replace("-",""),member,hash)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
            String scope="test-"+UUID.randomUUID().toString();jdbc.update("INSERT INTO note_discovery_daily_budget(budget_day,scope,scope_id) VALUES(CURRENT_DATE,'ACCEPTANCE',?)",scope);
            var counts=new ArrayList<Future<Integer>>();for(int i=0;i<10;i++)counts.add(pool.submit(()->jdbc.update("UPDATE note_discovery_daily_budget SET reserved_calls=reserved_calls+1 WHERE budget_day=CURRENT_DATE AND scope='ACCEPTANCE' AND scope_id=? AND reserved_calls<8",scope)));
            int granted=0;for(var count:counts)granted+=count.get();assertThat(granted).isEqualTo(8);
            System.out.println("MySQL 登记验收批次："+marker+"；租约/重启读取/唯一键/并发预算通过；不涉及模型调用或真实业务产物");
        } finally {pool.shutdownNow();jdbc.update("UPDATE note_discovery_schedule SET enabled=?,lease_token=NULL,lease_until=NULL WHERE member_id=?",enabled,member);}
    }
}
