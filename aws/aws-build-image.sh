#!/bin/bash
npm install
grunt deploy
./sbt assembly
docker build -f aws/fargate/Dockerfile -t viite:latest .
