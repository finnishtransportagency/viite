# Viite CI/CD Pipeline CDK Project

This project uses AWS CDK with TypeScript to define and deploy CloudFormation stacks for CI/CD pipelines for Dev and QA environments.

## Overview

The current CDK code generates CloudFormation templates that define:

1. CodePipeline pipelines for Dev and QA environments
2. CodeBuild projects for building and testing the Viite application
3. Necessary IAM roles and policies
4. S3 buckets for artifact storage
5. Connections to the GitHub repository
6. ECS service update actions for deployment
7. SNS Notifications for the pipeline

## Prerequisites

Ensure you have the following installed:

- **Node.js** (v14.x or later, Viite uses v20) 
- **AWS CLI**
- **AWS CDK**: Install with `npm install -g aws-cdk`

## Project Structure

- **`bin/`**: CDK app entry point
- **`lib/`**: CDK stack definition
- **`cdk.json`**: CDK configuration

## Setup and Deployment

### **Install dependencies:**

```bash
npm install
```
### AWS SSO Login

To use CDK, log in to AWS SSO:

```bash
aws sso login
```

### Navigate to the aws/cdk directory to use CDK commands:

```bash
cd aws/cdk
```

### Use CDK commands to interact with the project:

**Useful commands**

* **`cdk deploy`**  deploy this stack to AWS account/region
* **`cdk diff`**    compare deployed stack with current state
* **`cdk synth`**   emits the synthesized CloudFormation template

**Example how to deploy the CDK stack**

For Dev environment:
```bash
cdk deploy viite-dev-cicd-pipeline-stack
```
For QA environment:
```bash
cdk deploy viite-qa-cicd-pipeline-stack
```
   
    
    
