# Etapa 1: Compilación con Maven y Java 21
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Etapa 2: Ejecución ligera con Java 21 Runtime
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/target/motogo-backend-1.0-SNAPSHOT-jar-with-dependencies.jar app.jar

# Render asigna dinámicamente un puerto mediante la variable de entorno PORT
ENV PORT=7070
EXPOSE 7070

# Comando para arrancar la aplicación Java
ENTRYPOINT ["java", "-jar", "app.jar"]