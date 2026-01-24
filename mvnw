#!/bin/sh
# Maven Wrapper script

# Resolve script location
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

MAVEN_PROJECTBASEDIR="$SCRIPT_DIR"
export MAVEN_PROJECTBASEDIR

WRAPPER_JAR="$SCRIPT_DIR/.mvn/wrapper/maven-wrapper.jar"
WRAPPER_PROPERTIES="$SCRIPT_DIR/.mvn/wrapper/maven-wrapper.properties"

if [ ! -f "$WRAPPER_JAR" ]; then
    echo "Downloading Maven Wrapper..."
    
    if [ -f "$WRAPPER_PROPERTIES" ]; then
        WRAPPER_URL=$(grep 'wrapperUrl' "$WRAPPER_PROPERTIES" | cut -d'=' -f2-)
    fi
    
    if [ -z "$WRAPPER_URL" ]; then
        WRAPPER_URL="https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar"
    fi
    
    mkdir -p "$(dirname "$WRAPPER_JAR")"
    
    if command -v curl > /dev/null 2>&1; then
        curl -sL -o "$WRAPPER_JAR" "$WRAPPER_URL"
    elif command -v wget > /dev/null 2>&1; then
        wget -q -O "$WRAPPER_JAR" "$WRAPPER_URL"
    else
        echo "Error: Neither curl nor wget found. Cannot download Maven Wrapper."
        exit 1
    fi
fi

MAVEN_OPTS="${MAVEN_OPTS:--Xmx512m}"
export MAVEN_OPTS

exec java $MAVEN_OPTS \
    -Dmaven.multiModuleProjectDirectory="$MAVEN_PROJECTBASEDIR" \
    -classpath "$WRAPPER_JAR" \
    org.apache.maven.wrapper.MavenWrapperMain "$@"
