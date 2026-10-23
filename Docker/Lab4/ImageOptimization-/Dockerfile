FROM maven:3.9.15-eclipse-temurin-17-alpine AS builder
WORKDIR /app
COPY . .
RUN mvn package -B

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /app/target/demo-0.0.1-SNAPSHOT.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]