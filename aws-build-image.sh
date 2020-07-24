#!/bin/bash
npm install
yarn install --ignore-engines
grunt deploy
./sbt assembly
docker build -f aws/fargate/Dockerfile -t viite:latest .
