#!/bin/bash
npm install
npx grunt deploy
sbt clean assembly
docker build -f aws/fargate/Dockerfile -t viite:latest .
