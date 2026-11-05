FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -ntp clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app
RUN addgroup -S tradepulse && adduser -S -G tradepulse tradepulse
COPY --from=builder --chown=tradepulse:tradepulse /workspace/target/tradepulse-*.jar /app/tradepulse.jar

USER tradepulse

ENV PORT=8081
EXPOSE ${PORT}

ENTRYPOINT ["sh", "-c", "java -XX:+UseSerialGC -Xms128m -Xmx320m -Dserver.port=${PORT} -Dspring.data.mongodb.uri=${SPRING_DATA_MONGODB_URI} -jar /app/tradepulse.jar"]