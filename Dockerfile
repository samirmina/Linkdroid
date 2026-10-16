FROM ubuntu:24.04

# Install Java 21, ADB, and tools
RUN apt-get update && apt-get install -y \
    openjdk-21-jdk-headless \
    android-tools-adb \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy project files
COPY LinkdroidLauncher.java .
COPY lib/ ./lib/
COPY devices.json .
COPY settings.json .

# Compile with container's Java
RUN javac -cp "lib/*" LinkdroidLauncher.java

EXPOSE 27183-27199

ENTRYPOINT ["java", "-cp", "lib/*:.", "LinkdroidLauncher"]
