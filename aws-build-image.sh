#!/bin/bash
npm install
yarn install --ignore-engines
grunt deploy --target=production
./sbt assembly
docker build -f ci/fargate/Dockerfile -t viite:latest .
