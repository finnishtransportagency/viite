#!/bin/bash
# Before running this script you must be authenticated for the AWS CLI through Väylä authentication. See Confluence.
#
set -e

echo "Logging in to both dev/QA, and Prod AWS ECRs with Docker"
aws ecr get-login-password --profile vaylaapp --region eu-west-1 | docker login --username AWS --password-stdin 783354560127.dkr.ecr.eu-west-1.amazonaws.com
aws ecr get-login-password --profile vaylaapp --region eu-west-1 | docker login --username AWS --password-stdin 434599271542.dkr.ecr.eu-west-1.amazonaws.com
echo "Pull QA image"
docker pull 783354560127.dkr.ecr.eu-west-1.amazonaws.com/viite-qa:latest
echo "Tagging QA image into the Prod image"
docker tag 783354560127.dkr.ecr.eu-west-1.amazonaws.com/viite-qa:latest 434599271542.dkr.ecr.eu-west-1.amazonaws.com/viite-prod:latest
echo "Push the tagged image to Prod ECR repository"
docker push 434599271542.dkr.ecr.eu-west-1.amazonaws.com/viite-prod:latest
echo "Done."
echo "Ask centralized service to update the service (notes on how to do that are in the readme)"


