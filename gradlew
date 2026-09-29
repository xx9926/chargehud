#!/bin/sh
# ChargeHud wrapper launcher
APP_HOME=$(dirname "$0")
JAVACMD=java
if [ -n "$JAVA_HOME" ]; then JAVACMD="$JAVA_HOME/bin/java"; fi
exec "$JAVACMD" -Xmx64m -Dorg.gradle.appname=gradlew \
  -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain "$@"
