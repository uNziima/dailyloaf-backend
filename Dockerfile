FROM eclipse-temurin:21-jdk

# Daily Loaf backend — rebuild cache bust
WORKDIR /app

COPY src/ ./src/
COPY manifest.mf .

RUN find src -name "*.java" > sources.txt && \
    javac -d out @sources.txt && \
    jar cfm dailyloaf.jar manifest.mf -C out .

EXPOSE 8080

CMD ["java", "-cp", "dailyloaf.jar", "com.dailyloaf.Main"]