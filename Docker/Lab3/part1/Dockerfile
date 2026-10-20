FROM maven:3.9-eclipse-temurin-17

WORKDIR /app
COPY . .
RUN mvn package
EXPOSE 8080
CMD ["java", "-jar", "target/calculator.jar"]