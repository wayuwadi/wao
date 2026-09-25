package cn.wayuwa.wao.service;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** S3-compatible private bucket client; Garage credentials are supplied by the host environment. */
@Component
@ConditionalOnProperty(name = "wao.storage.mode", havingValue = "s3")
public class S3BinaryObjectStore implements BinaryObjectStore, AutoCloseable {
    private final S3Client client;
    private final String bucket;
    private final boolean retainMysqlCopy;

    public S3BinaryObjectStore(
            @Value("${wao.storage.endpoint}") String endpoint,
            @Value("${wao.storage.region:garage}") String region,
            @Value("${wao.storage.bucket}") String bucket,
            @Value("${wao.storage.access-key}") String accessKey,
            @Value("${wao.storage.secret-key}") String secretKey,
            @Value("${wao.storage.retain-mysql:true}") boolean retainMysqlCopy) {
        if (endpoint.isBlank() || bucket.isBlank() || accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalArgumentException("S3 object storage configuration is incomplete");
        }
        this.bucket = bucket;
        this.retainMysqlCopy = retainMysqlCopy;
        this.client = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false).build())
                .build();
    }

    @Override public boolean writesEnabled() { return true; }
    @Override public boolean retainMysqlCopy() { return retainMysqlCopy; }

    @Override public void put(String key, byte[] bytes, String contentType) {
        String hash = ImageService.sha256(bytes);
        if (!key.endsWith("/" + hash)) throw new IllegalArgumentException("Object key does not match bytes");
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
                        .metadata(java.util.Map.of("sha256", hash)).build(),
                RequestBody.fromBytes(bytes));
    }

    @Override public byte[] get(String key) {
        return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    @Override public void close() { client.close(); }
}
