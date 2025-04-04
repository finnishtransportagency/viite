#!/bin/bash
# Before running this script you must be authenticated for the AWS CLI through Väylä authentication. See Confluence.
#
set -e
echo "Building Docker image"
./aws/aws-build-image.sh
echo "Logging in to AWS ECR with Docker"
aws ecr get-login-password --profile vaylaapp --region eu-west-1 | docker login --username AWS --password-stdin 783354560127.dkr.ecr.eu-west-1.amazonaws.com
echo "Tagging image"
docker tag viite:latest 783354560127.dkr.ecr.eu-west-1.amazonaws.com/viite:latest
echo "Pushing image"
docker push 783354560127.dkr.ecr.eu-west-1.amazonaws.com/viite:latest

echo "Updating service"
# Using simply --force-new-deployment, because the service is using always the latest version of the container
aws ecs update-service --profile vaylaapp --region eu-west-1 --cluster VIITE-ECS-Cluster --service viite-dev --force-new-deployment
echo "Done."
