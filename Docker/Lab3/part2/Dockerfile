FROM eclipse-temurin:17-jre-alpine

RUN adduser -D appuser
WORKDIR /app
COPY target/calculator.jar app.jar
USER appuser

EXPOSE 8080
CMD ["java", "-jar", "app.jar"]