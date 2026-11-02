# 인증 서버 이미지. 빌드 컨텍스트는 저장소 루트다. (docker build -f auth-server/Dockerfile .)
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY auth-server/build.gradle auth-server/build.gradle
COPY auth-server/src auth-server/src
RUN ./gradlew :auth-server:bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre
ENV TZ=Asia/Seoul
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /workspace/auth-server/build/libs/auth-server-0.0.1-SNAPSHOT.jar app.jar
USER app
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
