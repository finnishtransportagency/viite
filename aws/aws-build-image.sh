#!/bin/bash
set -euo pipefail

npm ci
npm run deploy
sbt clean assembly
docker build -f aws/fargate/Dockerfile -t viite:latest .
