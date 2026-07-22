# OJ 프로젝트

Spring Boot와 Thymeleaf로 만든 온라인 저지다. 대회 제출은 웹 서버에서 저장한 뒤 RabbitMQ를 통해 judge 서버로 전달하고, 결과는 Redis scoreboard에 반영한다.

## 먼저 읽을 문서

1. [프로젝트 구조와 실행 흐름](docs/ARCHITECTURE.md)
2. [실행 환경과 자원 기준선](docs/ENVIRONMENT.md)
3. [대회 제출 파이프라인 개발 이력](docs/CONTEST_SUBMISSION_PIPELINE_HISTORY.md)
4. [초기 설계 기록](docs/OJ_DESIGN_NOTES.md)

## 기본 검증

```powershell
.\gradlew.bat test
.\gradlew.bat bootJar
docker compose config
docker compose up -d --build
docker compose ps
```

전체 실행은 MySQL, Redis, RabbitMQ, Nginx와 역할별 Spring 애플리케이션 5개로 구성된다. 로컬 DB만 사용하는 개발 방법보다 Compose 기준 실행을 우선한다.

## 측정 산출물

`var/`, `results/`, `results-standalone/`, `*.log`, `*.jfr`, `*.hprof`는 재생성 가능한 로컬 산출물이며 Git에서 추적하지 않는다. 측정 결과를 공유할 때는 원시 파일 대신 사용한 커밋, 실행 조건, 요약 수치를 문서에 남긴다.
