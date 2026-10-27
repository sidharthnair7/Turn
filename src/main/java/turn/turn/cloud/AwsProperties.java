package turn.turn.cloud;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where Turn publishes to AWS. An empty bucket turns cloud sync off.
 * name keeps several Turn instances apart in one bucket: the hosted demo uses "demo", so its guest page is
 * public/demo/index.html and never overwrites the restaurant's own public/index.html.
 */
@ConfigurationProperties("turn.aws")
public record AwsProperties(String bucket, String region, int everySec, String name) {

    public boolean enabled() {
        return bucket != null && !bucket.isBlank();
    }

    /** Public prefix for the guest page and wait board: "public/" or "public/<name>/". */
    public String publicPrefix() {
        return name == null || name.isBlank() ? "public/" : "public/" + name.strip() + "/";
    }

    /** Private prefix for shift summaries: "shifts/" or "shifts/<name>/". */
    public String shiftsPrefix() {
        return name == null || name.isBlank() ? "shifts/" : "shifts/" + name.strip() + "/";
    }
}
