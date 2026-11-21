# --- Stage 1 : Build Maven + extraction des couches Spring Boot ---
FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /build

# 1. On copie uniquement le pom.xml pour mettre en cache les dépendances Maven
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 2. On copie les sources et on compile le JAR
COPY src ./src
RUN mvn clean package -DskipTests -B

# 3. Extraction des couches (remplaçant officiel de layertools dans Spring Boot 4)
RUN java -Djarmode=tools -jar target/camel-app-0.0.1-SNAPSHOT.jar extract --layers --launcher --destination extracted

# --- Stage 2 : Image finale légère et durcie (JRE 25 non-root) ---
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Création d'un groupe et d'un utilisateur système dédiés (UID/GID 10001)
RUN addgroup -S -g 10001 appgroup && adduser -S -u 10001 -G appgroup appuser

# Copie des couches de la moins volatile à la plus volatile
COPY --from=builder /build/extracted/dependencies/ ./
COPY --from=builder /build/extracted/spring-boot-loader/ ./
COPY --from=builder /build/extracted/snapshot-dependencies/ ./
COPY --from=builder /build/extracted/application/ ./

USER 10001:10001

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]