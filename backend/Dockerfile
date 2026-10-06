# バックエンド: Mavenでビルドし、軽量なJREで実行する
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# 依存関係を先に取得する(ソースが変わっても、この層はキャッシュされる)
COPY pom.xml .
RUN mvn -B -ntp -q dependency:go-offline
COPY src ./src
RUN mvn -B -ntp -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
# 非rootユーザーで実行する
RUN addgroup -S app && adduser -S app -G app \
 && mkdir -p /app/uploads && chown -R app:app /app
WORKDIR /app
COPY --from=build /src/target/raisechat-backend-0.0.1-SNAPSHOT.jar app.jar
USER app
EXPOSE 8080
# ポートが開いているかで確認する(ヘルスチェック専用のAPIはない)
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=6 CMD nc -z localhost 8080 || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
