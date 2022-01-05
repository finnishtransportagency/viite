#!/bin/bash
#
# Registers new version of the task definition.
# Before this you must be authenticated. After running this you need to take the new task definition in use for the AWS service.
# If only the Docker container has changed, you don't need to run this.
# Usually you need to run this if environment variables have changed.


# === pre-requisite: Authenticate ===
# Before running this script you must be authenticated for the AWS CLI through Väylä SAML. From your project root, run:
#  (Note: when using this from windows, e.g. Bash seems to just hang -> use CMD instead)
#
# python3 aws/login/vaylaAssumeRoleAWSCLI.py --username <Your Väylä username> --account 783354560127 --role ViiteAdmin --region eu-west-1


#########################################################################
# NOTE Task-def registration, and takin into use are most probably done #
#      outside of the Viite team, and at different AWS account.         #
#      But added for completeness for now.                              #

# === the registration: ===
aws ecs register-task-definition --profile vaylaapp --region eu-west-1 --cli-input-json file://aws/task-definition/prod/prod-task-definition.json


# === after registration: take the new task definition into use for the service ===
# After running the registarion script, you can update the service to use this new task definition.
# Replace the <VERSION> with the new version. You can find the new version number from the start of the JSON returned by the previous command:
#         "taskDefinitionArn": "arn:aws:ecs:eu-west-1:783354560127:task-definition/Viite-prod:<VERSION>",
#
# Viite-QA-ALB-stack:
# aws ecs update-service --profile vaylaapp --region eu-west-1 --cluster prod-viite-ECS-Cluster-Private  --service prod-viite-ECS-Service-Private --task-definition prod-viite:<VERSION> --force-new-deployment

#                                                                       #
#########################################################################