package my.oj.web.submission;

public enum SubmissionResult {
    ACCEPTED,      // 정답
    PARTIAL_ACCEPTED, // 부분 정답
    WRONG_ANSWER,  // 오답
    TIME_LIMIT,    // 시간 초과
    MEMORY_LIMIT,  // 메모리 초과
    RUNTIME_ERROR, // 런타임 에러
    COMPILATION_ERROR, // 컴파일 에러
    PENDING,       // 아직 판정 안 됨
    SYSTEM_ERROR
}
