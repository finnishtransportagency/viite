######################################
# Check template / Create / Deploy / Update Viite production ALB stack.
# Name for the stack used here: ViiteProdFargate.
# Files referenced as paremeters to the commands can be found from <viite-Git-root>/aws/cloud-formation/prod/.
######################################

# === pre-requisite: Authenticate ===
# Before running this script you must authenticated for the AWS CLI

#####################
# Creating the stack:

## check the correctness of the template
# aws cloudformation validate-template --profile <cs-vayla-account-profile> \
#     --template-body file://prod-viite-alb_ecs.yaml

## create the ALB stack
# aws cloudformation create-stack \
     --profile <cs-vayla-account-profile> --region eu-west-1 \
     --stack-name ViiteProdFargate \
     --template-body file://prod-viite-alb_ecs.yaml --parameters file://prod-parameters-viite-alb_ecs.json \
     --on-failure DELETE --capabilities CAPABILITY_NAMED_IAM \


#####################
# Updating the stack:
# (The stack will probably mostly stay the same. But in case the changes are required)

## check the correctness of the template
# aws cloudformation validate-template --profile <cs-vayla-account-profile> \
#     --template-body file://prod-viite-alb_ecs.yaml

## create a changeset before updating the stack ("--no-execute-changeset")
# (Note: Giving a file to the --parameter-overrides requires a rather new AWS CLI to function.)
# aws cloudformation deploy \
     --profile <cs-vayla-account-profile> --region eu-west-1 \
     --stack-name ViiteProdFargate \
     --template-file prod-viite-alb_ecs.yaml \
     --parameter-overrides file://prod-parameters-viite-alb_ecs.json \
     --no-execute-changeset
#
# ...and check the changes with the command suggested before updating the stack.

## update
# aws cloudformation update-stack \
     --profile <cs-vayla-account-profile> \
     --stack-name ViiteProdFargate \
     --template-body file://prod-viite-alb_ecs.yaml \
     --parameters file://prod-parameters-viite-alb_ecs.json \
     --capabilities CAPABILITY_NAMED_IAM
