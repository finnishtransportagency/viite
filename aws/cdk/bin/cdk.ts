#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import { ViiteCdkStack } from '../lib/cdk-stack';

const app = new cdk.App();

new ViiteCdkStack(app, 'viite-dev-cicd-pipeline-stack', {

    env: {
        account: '783354560127',
        region: 'eu-west-1',
    },
    environment: 'Dev', // Use 'QA' for the QA environment
    pipelineName: 'viite-dev-pipeline',
    buildProjectName: 'viite-dev-build',
    gitHubBranch: 'postgis',
    artifactBucketName: 'fi-viite-dev',
    ecrRepositoryName: 'viite',
    securityGroupId: 'sg-0feb523b1d68c19e2',
    kmsKeyArn: 'arn:aws:kms:eu-west-1:783354560127:alias/aws/s3',
    vpcId: 'vpc-017797e470d94956b',
    ecsClusterName: 'Viite-ECS-Cluster-Private',
    ecsServiceName: 'Viite-ECS-Service-Private'

});
