package cn.wayuwa.wao.ops;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Temporary in-container acceptance using the deployed Spring Data/Lettuce libraries. */
public final class RedisHaProbe {
    public static void main(String[] args) throws Exception {
        int seconds = Integer.parseInt(args[0]);
        if (seconds < 10 || seconds > 240) throw new IllegalArgumentException("Duration out of range");
        String password = System.getenv("REDIS_PASS");
        if (password == null || password.isEmpty()) throw new IllegalStateException("Missing configured credential");
        RedisSentinelConfiguration sentinel = new RedisSentinelConfiguration("wao-master", Set.of(
                "192.168.31.180:26379", "192.168.2.180:26379", "10.66.0.1:26379"));
        sentinel.setPassword(RedisPassword.of(password));
        sentinel.setSentinelPassword(RedisPassword.of(password));
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(3)).shutdownTimeout(Duration.ofSeconds(1)).build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(sentinel, client);
        factory.afterPropertiesSet();
        factory.start();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        String key = "wao:infra-acceptance:" + UUID.randomUUID();
        String value = UUID.randomUUID().toString();
        long started = System.nanoTime();
        int failures = 0;
        int successes = 0;
        String lastRunId = "";
        try {
            redis.opsForValue().set(key, value, Duration.ofMinutes(10));
            System.out.println("PROBE READY");
            while (System.nanoTime() - started < Duration.ofSeconds(seconds).toNanos()) {
                try {
                    if (!value.equals(redis.opsForValue().get(key))) {
                        throw new AssertionError("Confirmed test value was lost");
                    }
                    redis.opsForValue().set(key + ":tick", Long.toString(System.nanoTime()), Duration.ofMinutes(10));
                    try (RedisConnection connection = factory.getConnection()) {
                        String runId = connection.serverCommands().info("server").getProperty("run_id");
                        if (!runId.equals(lastRunId)) {
                            System.out.printf("PROBE PRIMARY_CHANGED elapsedMs=%d runId=%s%n",
                                    (System.nanoTime() - started) / 1_000_000, runId);
                            lastRunId = runId;
                        }
                    }
                    successes++;
                    if (successes % 5 == 0) System.out.println("PROBE READ_WRITE_OK count=" + successes);
                } catch (RuntimeException failure) {
                    failures++;
                    System.out.printf("PROBE TEMPORARY_FAILURE elapsedMs=%d type=%s%n",
                            (System.nanoTime() - started) / 1_000_000, failure.getClass().getSimpleName());
                }
                Thread.sleep(1000);
            }
            if (successes == 0 || !value.equals(redis.opsForValue().get(key))) {
                throw new AssertionError("Probe did not recover");
            }
            System.out.printf("PROBE COMPLETE successes=%d temporaryFailures=%d%n", successes, failures);
        } finally {
            try { redis.delete(java.util.List.of(key, key + ":tick")); }
            finally { factory.destroy(); }
        }
    }
}
