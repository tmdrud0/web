package my.oj.web.submission;

public enum SubmissionSortOrder {
    ASC,
    DESC;

    public static SubmissionSortOrder from(String value) {
        if (value == null) {
            return DESC;
        }
        return "asc".equalsIgnoreCase(value) ? ASC : DESC;
    }
}

