#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import { ViiteCdkStack } from '../lib/cdk-pipeline-stack';

const app = new cdk.App();

new ViiteCdkStack(app, 'viite-dev-cicd-pipeline-stack', {

    // cdk <command> viite-dev-cicd-pipeline-stack

    env: {
        account: '783354560127',
        region: 'eu-west-1',
    },
    environment: 'Dev',
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
/* For QA environment
new ViiteCdkStack(app, 'viite-QA-cicd-pipeline-stack', {

    // cdk <command> viite-QA-cicd-pipeline-stack

    env: {
        account: '783354560127',
        region: 'eu-west-1',
    },
    environment: 'QA',
    pipelineName: 'viite-qa-pipeline',
    buildProjectName: 'viite-qa-build',
    gitHubBranch: 'NextRelease',
    artifactBucketName: 'codepipeline-eu-west-1-165738134933',
    ecrRepositoryName: 'viite-qa',
    securityGroupId: 'sg-0b9b3751179bbf3b1',
    kmsKeyArn: 'arn:aws:kms:eu-west-1:783354560127:alias/aws/s3',
    vpcId: 'vpc-005d0e40bb418a818',
    ecsClusterName: 'QA-viite-test-ECS-Cluster-Private',
    ecsServiceName: 'QA-viite-test-ECS-Service-Private'

});
*/
