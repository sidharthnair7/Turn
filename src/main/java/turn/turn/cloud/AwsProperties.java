package turn.turn.cloud;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where Turn publishes to AWS. An empty bucket turns cloud sync off. */
@ConfigurationProperties("turn.aws")
public record AwsProperties(String bucket, String region, int everySec) {

    public boolean enabled() {
        return bucket != null && !bucket.isBlank();
    }
}
