# 코어 백엔드 이미지. 빌드 컨텍스트는 저장소 루트다. (docker compose --profile app 이 사용)
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
# settings.gradle 이 auth-server 를 포함하므로 빌드 설정 파일도 함께 둔다 (소스는 필요 없다)
COPY auth-server/build.gradle auth-server/build.gradle
COPY src src
RUN ./gradlew :bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre
ENV TZ=Asia/Seoul
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /workspace/build/libs/insur-core-system-0.0.1-SNAPSHOT.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
