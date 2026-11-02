# insur-core-system

손해보험 기간계 시스템입니다. 설계사, 심사, 계약관리, 관리자 같은 보험사 현업 직원이 청약 접수부터 수납, 승인, 배서, 해지, 갱신까지 처리합니다.

## 구성

```
브라우저 ──▶ web (nginx :8088)
              ├─ /          React 화면 (frontend/)
              ├─ /api/**  ─▶ core  (코어 백엔드 :8080)  계약·보험료·업무 API
              └─ /auth/** ─▶ auth  (인증 서버 :8081)    로그인·JWT 발급·사원 관리
                                │                 │
                                └── PostgreSQL ───┘  public 스키마(코어), auth 스키마(인증)
```

| 모듈 | 위치 | 역할 |
|---|---|---|
| 코어 백엔드 | `src/` | 청약, 계약 조회, 상태 변경, 수납, 배서, 갱신, 보험료 산출, 요율 관리, 업무 현황 API. 토큰을 발급하지 않고 검증만 합니다. |
| 인증 서버 | `auth-server/` | 사원 계정과 역할, 로그인(5회 오류 잠금), 비밀번호 변경, JWT 발급, 사원 관리, 로그인 이력 |
| 현업 화면 | `frontend/` | React + Vite(JavaScript). 역할에 맞는 메뉴와 버튼만 보여 줍니다. |

두 서버는 같은 비밀키(`JWT_SECRET`)를 씁니다. 인증 서버가 서명한 토큰을 코어가 그대로 검증합니다.

## 화면과 역할

| 메뉴 | 설계사 | 심사 | 계약관리 | 관리자 |
|---|:-:|:-:|:-:|:-:|
| 업무 현황, 할 일함, 계약 조회, 보험료 산출, 만기·갱신 대상 | ● | ● | ● | ● |
| 청약 입력, 갱신 | ● | | | ● |
| 승인, 청약 취소 | | ● | | ● |
| 수납, 해지 | | | ● | ● |
| 배서 | | ● | ● | ● |
| 요율·계수·정책 조회 / 개정 | | 조회 | 조회 | ● |
| 사원·권한 관리, 로그인 이력 | | | | ● |

설계사는 본인이 접수한 계약만 볼 수 있습니다.

## 실행

### 개발 (각자 띄우기)

```bash
docker compose up -d                                  # PostgreSQL
SPRING_PROFILES_ACTIVE=local ./gradlew :bootRun       # 코어 :8080
SPRING_PROFILES_ACTIVE=local ./gradlew :auth-server:bootRun   # 인증 :8081 (개발용 계정 생성)
cd frontend && npm install && npm run dev             # 화면 :5173
```

http://localhost:5173 에서 아래 개발용 계정으로 로그인합니다. 비밀번호는 모두 `Insur!2026`이고, 인증 서버의 `local` 프로필에서만 만들어집니다.

| 사번 | 역할 |
|---|---|
| admin01 | 관리자 |
| agent01, agent02 | 설계사 |
| uw01 | 심사 |
| op01 | 계약관리 |

### 컨테이너로 한 번에

```bash
JWT_SECRET=<32바이트 이상 임의 문자열> AUTH_PROFILES=local docker compose --profile app up -d --build
```

http://localhost:8088 로 접속합니다. `AUTH_PROFILES=local`을 빼면 개발용 계정 없이 뜹니다. 이때는 `AUTH_BOOTSTRAP_ADMIN_PASSWORD`로 첫 관리자(`admin`)를 만들고, 첫 로그인 때 비밀번호를 바꿉니다.

### 테스트

```bash
docker compose up -d
./gradlew test        # 코어 + 인증 서버 (실제 DB 사용)
```

## 문서

- `CLAUDE.md`: 설정, 업무 규칙, 오류코드 등 개발 규약
- `docs/architecture.html`: 청약 처리 구조
- `docs/study/`: 자바 백엔드 학습 문서
