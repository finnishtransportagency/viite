# CodeArtifact Setup Guide

## Introduction

The Viite project uses AWS CodeArtifact to store and manage our npm and Maven packages. 

**Note for Local Development:**
While CodeArtifact is used for our CI/CD pipeline, its use is optional for local development. You can choose to develop using public package repositories if you prefer. 

This guide explains how to set up CodeArtifact for both npm (frontend) and SBT/Maven (backend) dependencies, should you choose to use it for your local development.

## Prerequisites

- AWS CLI installed and configured
- Node.js and npm installed (for frontend)
- SBT installed (for backend)

**Note**: The AWS CodeArtifact AuthToken is valid for 12 hours. You will need to redo next steps every time you choose to use CodeArtifact for packages.

## AWS SSO Login

Before setting up CodeArtifact, log in to AWS SSO:

```bash
aws sso login --profile <profilename>
```
Replace <profilename> with your AWS profile name.

## CodeArtifact npm Setup 

Log in to the npm repository:

```bash
aws codeartifact login --tool npm --repository viite_npm_packages --domain vayla-viite --domain-owner 783354560127
```
Install dependencies:

```bash
npm install
```

## CodeArtifact SBT/Maven Setup 

#### Setting CodeArtifact Authorization Token

For Bash/PowerShell:

```bash
export CODE_ARTIFACT_AUTH_TOKEN=$(aws codeartifact get-authorization-token --domain vayla-viite --domain-owner 783354560127 --region eu-west-1 --query authorizationToken --output text)
```

For Windows Command Prompt:

```cmd
for /f "tokens=*" %a in ('aws codeartifact get-authorization-token --domain vayla-viite --domain-owner 783354560127 --region eu-west-1 --query authorizationToken --output text') do set CODE_ARTIFACT_AUTH_TOKEN=%a
```
Run SBT commands as usual:

```bash
sbt update
```

## Switching Between CodeArtifact and Public Repositories

### npm (Frontend)

To switch back to the public npm registry:

1. Remove the CodeArtifact configuration:

```bash
npm config delete registry
```

2. Install dependencies:

```bash
npm install
```
### SBT (Backend)

To switch back to public Maven repositories for SBT:

1. Unset the CodeArtifact authentication token:

For Bash/PowerShell:
```bash
unset CODE_ARTIFACT_AUTH_TOKEN
```
For Windows Command Prompt:
```cmd
set CODE_ARTIFACT_AUTH_TOKEN=
``` 
2. Run SBT commands as usual:

```bash
sbt update
```


