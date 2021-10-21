#!/bin/bash
# Before running this script you must authenticate through Väylä SAML:
#
# python3 aws/login/vaylaAssumeRoleAWSCLI.py --username <Your Väylä username> --account 783354560127 --role ViiteAdmin --region eu-west-1
#
set -e

echo "Logging in to AWS ECR with Docker"
aws ecr get-login-password --profile vaylaapp --region eu-west-1 | docker login --username AWS --password-stdin 783354560127.dkr.ecr.eu-west-1.amazonaws.com
echo " Pull dev-test image"
docker pull 783354560127.dkr.ecr.eu-west-1.amazonaws.com/viite:latest
echo "Tagging image"
docker tag 783354560127.dkr.ecr.eu-west-1.amazonaws.com/viite:latest 783354560127.dkr.ecr.eu-west-1.amazonaws.com/viite-qa:latest
echo "Pushing image"
docker push 783354560127.dkr.ecr.eu-west-1.amazonaws.com/viite-qa:latest

echo "Updating service"
# Using simply --force-new-deployment, because the service is using always the latest version of the container
aws ecs update-service --profile vaylaapp --region eu-west-1 --cluster QA-viite-test-ECS-Cluster-Private --service QA-viite-test-ECS-Service-Private --force-new-deployment
echo "Done."
