#!/bin/bash
grunt
./sbt assembly
docker build -f ci/fargate/Dockerfile -t viite:latest .
