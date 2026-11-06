#!/bin/sh
# Compiles and tests the backend WITHOUT Maven, using the JDK plus jars from the Ubuntu archive.
# Needs: JDK 21, libjackson2-{core,databind,annotations}-java, libpostgresql-jdbc-java, junit5 (console standalone).
# Database tests use TEST_DB_URL (default: localhost trainmate_test); Redis tests use REDIS_URL. Both skip if unreachable.
# Everything under src/main/java/app/trainmate/spring is the Spring Boot host and is NOT compiled here.
set -e
cd "$(dirname "$0")/.."
J=/usr/share/java
LIBS=$J/jackson-core.jar:$J/jackson-databind.jar:$J/jackson-annotations.jar:$J/postgresql.jar
JUNIT=$J/junit-platform-console-standalone.jar
rm -rf build && mkdir -p build/main build/test
find src/main/java -name '*.java' | grep -v '/spring/' > build/main-src.txt
javac -Xlint:all -d build/main -cp "$LIBS" --release 21 @build/main-src.txt
find src/test/java -name '*.java' > build/test-src.txt
javac -Xlint:all -d build/test -cp "build/main:$LIBS:$JUNIT" --release 21 @build/test-src.txt
[ -d src/main/resources ] && cp -r src/main/resources/. build/main/
# TEST_CLASS=app.trainmate.auth.FirebaseTokenVerifierTest runs one class; otherwise everything.
if [ -n "$TEST_CLASS" ]; then SELECT="--select-class $TEST_CLASS"; else SELECT="--scan-classpath build/test"; fi
exec java -jar "$JUNIT" execute -cp "build/main:build/test:$LIBS" $SELECT --disable-banner --details=summary "$@"
