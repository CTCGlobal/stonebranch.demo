#!/bin/bash

# Java Version Checker and Setup Script
# This script checks Java version compatibility and provides solutions

echo "Checking Java version..."

# Check current Java version
JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2)
echo "Current Java version: $JAVA_VERSION"

# Check if Java 17 is installed
if [[ "$JAVA_VERSION" == *"17."* ]]; then
    echo "Java 17 is already installed and set up correctly."
    exit 0
fi

# Check if Java 17 is available in the system
JAVA17_PATH=$(which java17 2>/dev/null)
if [ -n "$JAVA17_PATH" ]; then
    echo "Java 17 found at: $JAVA17_PATH"
    echo "Please set your JAVA_HOME to point to Java 17 installation"
    exit 1
fi

# Check if we're on macOS and can use Homebrew to install Java 17
if [[ "$OSTYPE" == "darwin"* ]]; then
    echo "On macOS, you can install Java 17 using Homebrew:"
    echo "brew install openjdk@17"
    echo ""
    echo "After installation, set JAVA_HOME:"
    echo "export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
    echo "export PATH=$JAVA_HOME/bin:$PATH"
    echo ""
    echo "Then run: source ~/.bashrc or source ~/.zshrc"
else
    echo "Please install Java 17 and set JAVA_HOME accordingly"
    echo "For Ubuntu/Debian: sudo apt install openjdk-17-jdk"
    echo "For CentOS/RHEL: sudo yum install java-17-openjdk-devel"
fi

echo ""
echo "To compile with Maven, you may also need to add this to your pom.xml:"
echo "<properties>"
echo "    <maven.compiler.source>17</maven.compiler.source>"
echo "    <maven.compiler.target>17</maven.compiler.target>"
echo "</properties>"