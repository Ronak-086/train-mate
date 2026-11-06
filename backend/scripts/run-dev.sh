#!/bin/sh
# Compiles and runs the API on the plain JDK server (no Spring, no Maven). For local development and the full-stack browser tests.
# Needs the same jars as test-offline.sh and a PostgreSQL database (MIGRATE=1 builds the schema if the database is empty).
set -e
cd "$(dirname "$0")/.."
J=/usr/share/java
LIBS=$J/jackson-core.jar:$J/jackson-databind.jar:$J/jackson-annotations.jar:$J/postgresql.jar
rm -rf build/run && mkdir -p build/run
find src/main/java -name '*.java' | grep -v '/spring/' > build/run-src.txt
javac -d build/run -cp "$LIBS" --release 21 @build/run-src.txt
cp -r src/main/resources/. build/run/
exec java -cp "build/run:$LIBS" app.trainmate.JdkMain
