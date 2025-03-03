# AWS CloudFormation - Development Environment

### Clone the repository and checkout the postgis branch

```
git clone https://github.com/finnishtransportagency/viite.git
cd viite
git checkout origin/postgis
```
### Set environment variables
Note: Environment variables are only valid for the current shell / cmd session

*Windows Command Prompt*
```
setx AWS_DEFAULT_REGION eu-west-1
setx AWS_PROFILE vaylaapp
```

*Linux / macOS*
```
export AWS_DEFAULT_REGION=eu-west-1
export AWS_PROFILE=vaylaapp
```

## Task Definition Update

### Create a new task definition version

```
aws cloudformation update-stack \
--stack-name [e.g. viite-dev-taskdefinition] \
--capabilities CAPABILITY_NAMED_IAM \
--template-body file://aws/cloud-formation/dev/dev-task-definition.yaml \
--parameters ParameterKey=RepositoryURL,ParameterValue=[URL to repository where the container is located, e.g. 012345678910.dkr.ecr.eu-west-1.amazonaws.com]
```

### Update the service

Deploy the newly created task definition version.
Note: Omitting the [:VERSION] part will use the latest task definition version.

```
aws ecs update-service \
--cluster Viite-ECS-Cluster-Private \
--service Viite-ECS-Service-Private \
--task-definition Viite[:VERSION] \
--force-new-deployment
```
