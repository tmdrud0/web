package my.oj.web.perf.dto;

public record SeedRequest(int totalUsers, int batchSize) {
    public SeedRequest {
        if (totalUsers < 0) {
            throw new IllegalArgumentException("totalUsers must be non-negative");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
    }
}
