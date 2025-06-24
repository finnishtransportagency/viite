#!/bin/bash
# This script builds a Docker image and pushes it to AWS Dev environment ECR repository.
# It can be used for initial deployment when pipeline is not set up yet.
# It assumes that the AWS CLI is configured with a profile named "Viite-dev".
# The script also assumes that the AWS CLI is installed and configured on the machine where it is run.
# The script uses the AWS CLI to get the account ID and construct the ECR repository URI.
# It also assumes that the Docker is running and that the user has permission to push images to the ECR repository.
#
# Before running this script you must authenticate through AWS SSO:
#
# aws sso login --profile Viite-dev
#
set -e

# Set environment
ENV="dev"
AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --region eu-west-1 --output text)
ECR_REPOSITORY_NAME=viite-${ENV}-ecr-repository
REPOSITORY_URI=${AWS_ACCOUNT_ID}.dkr.ecr.eu-west-1.amazonaws.com/${ECR_REPOSITORY_NAME}

echo "Building Docker image"
./aws/aws-build-image.sh
echo "Logging in to AWS ECR with Docker"
aws ecr get-login-password --profile Viite-dev --region eu-west-1 | docker login --username AWS --password-stdin ${AWS_ACCOUNT_ID}.dkr.ecr.eu-west-1.amazonaws.com
echo "Tagging image"
docker tag viite:latest ${REPOSITORY_URI}:${ENV}
echo "Pushing image"
docker push ${REPOSITORY_URI}:${ENV}

echo "Image successfully pushed to ${REPOSITORY_URI}:${ENV}"
