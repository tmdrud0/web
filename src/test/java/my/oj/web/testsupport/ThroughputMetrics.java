package my.oj.web.testsupport;

public record ThroughputMetrics(long operations,
                                double seconds,
                                double perSecond,
                                double perMinute) {

    public static ThroughputMetrics of(long operations, long nanos) {
        double seconds = nanos / 1_000_000_000.0;
        double perSecond = seconds > 0 ? operations / seconds : 0.0;
        double perMinute = perSecond * 60.0;
        return new ThroughputMetrics(operations, seconds, perSecond, perMinute);
    }

    public String summary(String unit) {
        return String.format("%d %s in %.2f s (%.2f %s/s, %.2f %s/min)",
                operations,
                unit,
                seconds,
                perSecond,
                unit,
                perMinute,
                unit);
    }
}

