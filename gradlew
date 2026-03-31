#!/usr/bin/env sh

#
# Copyright 2015 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
DEFAULT_JVM_OPTS=""

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`

# Use the maximum available, or set MAX_FD != -1 to use that value.
MAX_FD="maximum"

# OS specific support.  $var _must_ be set to either true or false.
cygwin=false
msys=false
darwin=false
nonstop=false
case "`uname`" in
  CYGWIN* )
    cygwin=true
    ;;
  Darwin* )
    darwin=true
    ;;
  MINGW* )
    msys=true
    ;;
  NONSTOP* )
    nonstop=true
    ;;
esac

# Attempt to set APP_HOME
# Resolve links: $0 may be a link
PRG="$0"
# Need this for relative symlinks.
while [ -h "$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=`dirname "$PRG"`"/$link"
    fi
done
SAVED="`pwd`"
cd "`dirname \"$PRG\"`/" >/dev/null
APP_HOME="`pwd -P`"
cd "$SAVED" >/dev/null

# For Cygwin, ensure paths are in UNIX format before anything is touched
if $cygwin ; then
    [ -n "$APP_HOME" ] &&
        APP_HOME=`cygpath --unix "$APP_HOME"`
    [ -n "$JAVA_HOME" ] &&
        JAVA_HOME=`cygpath --unix "$JAVA_HOME"`
fi

# Attempt to locate JAVA_HOME if not already set.
if [ -z "$JAVA_HOME" ] ; then
    if $darwin ; then
        [ -x '/usr/libexec/java_home' ] && JAVA_HOME=`/usr/libexec/java_home`
    else
        java_path=`which java 2>/dev/null`
        if [ "x$java_path" != "x" ] ; then
            java_path=`dirname "$java_path" 2>/dev/null`
            JAVA_HOME=`dirname "$java_path" 2>/dev/null`
        fi
    fi
fi

# Read relative path to Gradle distribution from wrapper properties
WRAPPER_PROPS="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"
if [ -f "$WRAPPER_PROPS" ]; then
    . "$WRAPPER_PROPS"
fi
if [ -z "$distributionUrl" ]; then
    echo "ERROR: Could not read '$WRAPPER_PROPS' or 'distributionUrl' is not set." >&2
    exit 1
fi

# For Cygwin, switch paths to Windows format before running java
if $cygwin ; then
    APP_HOME=`cygpath --path --windows "$APP_HOME"`
    JAVA_HOME=`cygpath --path --windows "$JAVA_HOME"`
    CLASSPATH=`cygpath --path --windows "$CLASSPATH"`
fi

# Split the distributionUrl into path and query components
url_path=${distributionUrl%%\?*}
url_query=${distributionUrl#*\?}

# Extract the distribution base name from the distributionUrl
dist_name=$(basename "${url_path}")
dist_base_name=${dist_name%.*}

# Construct the local file name and path for the distribution
dist_path="gradle/wrapper/dists/$dist_base_name"
dist_file="$dist_path/$dist_name"

# Download the distribution if it does not exist
if [ ! -f "$dist_file" ]; then
    mkdir -p "$dist_path"
    echo "Downloading $distributionUrl"
    if command -v curl >/dev/null 2>&1; then
        curl -L -o "$dist_file" "$distributionUrl"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$dist_file" "$distributionUrl"
    else
        echo "ERROR: Neither curl nor wget is available to download the Gradle distribution." >&2
        exit 1
    fi
fi

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # IBM's JDK on AIX uses strange locations for the executables
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
fi

# Increase the maximum number of open file descriptors
if [ "$MAX_FD" != "" ] ; then
    if ! ulimit -n "$MAX_FD" ; then
        echo "Could not set maximum file descriptor limit: $MAX_FD"
    fi
fi

# Add default JVM options
if [ "x$DEFAULT_JVM_OPTS" != "x" ]; then
    GRADLE_OPTS="$DEFAULT_JVM_OPTS $GRADLE_OPTS"
fi

# Setup the GRADLE_OPTS variable
if [ -z "$GRADLE_OPTS" ] ; then
    GRADLE_OPTS="-Xmx64m -Xms64m"
fi

# Setup the CLASSPATH
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# Execute Gradle
exec "$JAVACMD" $GRADLE_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
