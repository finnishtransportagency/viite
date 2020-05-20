#!/bin/bash
# Before running this script you must authenticate through Väylä SAML:
#
# python3 ~/bin/vaylaAssumeRoleAWSCLI.py --username <Your Väylä username> --account 783354560127 --role ViiteAdmin --region eu-west-1
#
aws ecs register-task-definition --profile vaylaapp --region eu-west-1 --cli-input-json file://aws/task-definition/dev/task-definition.json
