#!/bin/bash

# Spring Boot Application Runner Script
# This script compiles and runs the Spring Boot application

set -e  # Exit on any error

echo "Starting Spring Boot Application..."

# Check if Maven is available
if command -v mvn &> /dev/null; then
    echo "Using Maven to build and run the application"
    mvn spring-boot:run
elif command -v gradle &> /dev/null; then
    echo "Using Gradle to build and run the application"
    gradle bootRun
else
    echo "Error: Neither Maven nor Gradle is installed"
    echo "Please install Maven or Gradle to build and run this application"
    exit 1
fi

echo "Application started successfully!"