#!/bin/bash
# Before running this script you must be authenticated for the AWS CLI through Väylä authentication. See Confluence.
#
set -e

echo "Logging in to AWS ECR with Docker"
aws ecr get-login-password --profile vaylaapp --region eu-west-1 | docker login --username AWS --password-stdin 783354560127.dkr.ecr.eu-west-1.amazonaws.com
echo "Pull dev image"
docker pull 783354560127.dkr.ecr.eu-west-1.amazonaws.com/viite:latest
echo "Tagging dev image as also QA image"
docker tag 783354560127.dkr.ecr.eu-west-1.amazonaws.com/viite:latest 783354560127.dkr.ecr.eu-west-1.amazonaws.com/viite-qa:latest
echo "Push the just tagged QA image to dev/QA repository"
docker push 783354560127.dkr.ecr.eu-west-1.amazonaws.com/viite-qa:latest

echo "Updating service"
# Using simply --force-new-deployment, because the service is using always the latest version of the container
aws ecs update-service --profile vaylaapp --region eu-west-1 --cluster QA-viite-test-ECS-Cluster-Private --service QA-viite-test-ECS-Service-Private --force-new-deployment
echo "Done."
