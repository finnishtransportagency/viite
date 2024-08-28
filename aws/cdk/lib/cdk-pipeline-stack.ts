import * as cdk from 'aws-cdk-lib';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as codepipeline from 'aws-cdk-lib/aws-codepipeline';
import * as codepipeline_actions from 'aws-cdk-lib/aws-codepipeline-actions';
import * as codebuild from 'aws-cdk-lib/aws-codebuild';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import { Construct } from 'constructs';
import { Secret } from "aws-cdk-lib/aws-secretsmanager";
import * as codestarnotifications from "aws-cdk-lib/aws-codestarnotifications";
import * as sns from "aws-cdk-lib/aws-sns";
import {Duration} from "aws-cdk-lib";

interface ViiteCdkStackProps extends cdk.StackProps {
  environment: 'Dev' | 'QA';
  pipelineName: string;
  buildProjectName: string;
  gitHubBranch: string;
  artifactBucketName: string;
  ecrRepositoryName: string;
  securityGroupId: string;
  vpcId: string;
  ecsClusterName: string;
  ecsServiceName: string;
}

export class ViiteCdkStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: ViiteCdkStackProps) {
    super(scope, id, props);

    const { environment, pipelineName, buildProjectName, gitHubBranch, artifactBucketName, ecrRepositoryName, securityGroupId, vpcId, ecsClusterName, ecsServiceName } = props;

    // Add a description for the stack
    this.templateOptions.description = `Viite CI/CD Pipeline Stack for ${environment} environment including CodeBuild projects, CodePipeline, and associated resources.`;

    // Import existing resources
    const artifactBucket = s3.Bucket.fromBucketName(this, 'ExistingBucket', artifactBucketName);
    const ecrRepository = ecr.Repository.fromRepositoryName(this, 'ExistingECRRepo', ecrRepositoryName);
    const vpc = ec2.Vpc.fromLookup(this, 'ExistingVPC', {
      vpcId: vpcId
    });
    const vpcSubnets = vpc.selectSubnets({
      subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS
    });
    const securityGroup = ec2.SecurityGroup.fromSecurityGroupId(this, 'ExistingSecurityGroup', securityGroupId);
    const ecsCluster = ecs.Cluster.fromClusterAttributes(this, 'ExistingECSCluster', {
      clusterName: ecsClusterName,
      vpc
    });
    const ecsService = ecs.FargateService.fromFargateServiceAttributes(this, 'ExistingECSService', {
      serviceName: ecsServiceName,
      cluster: ecsCluster,
    });

    // CodeBuild Project
    const buildProject = new codebuild.PipelineProject(this, 'BuildProject', {
      projectName: buildProjectName,
      environment: {
        buildImage: codebuild.LinuxBuildImage.STANDARD_5_0,
        computeType: codebuild.ComputeType.SMALL,
        privileged: true,
        environmentVariables: {
          bonecpUsername: { value: '/dev/viite_test.db.username', type: codebuild.BuildEnvironmentVariableType.PARAMETER_STORE },
          bonecpPassword: { value: '/dev/viite_test.db.password', type: codebuild.BuildEnvironmentVariableType.PARAMETER_STORE },
          bonecpJdbcUrl: {
            value: `/Viite/${environment}/bonecpJdbcUrl`,
            type: codebuild.BuildEnvironmentVariableType.PARAMETER_STORE
          },
          vvhRestApiEndPoint: { value: 'vvhRestApiEndPoint', type: codebuild.BuildEnvironmentVariableType.PARAMETER_STORE },
          vvhServiceHost: { value: 'vvhServiceHost', type: codebuild.BuildEnvironmentVariableType.PARAMETER_STORE },
          vkmUrl: { value: 'vkmUrl', type: codebuild.BuildEnvironmentVariableType.PARAMETER_STORE },
          JAVA_OPTS: { value: '-Xms512M -Xmx1024M -Xss1M -XX:+CMSClassUnloadingEnabled' },
          conversionBonecpJdbcUrl: { value: '/allEnvs/conversion.jdbcUrl', type: codebuild.BuildEnvironmentVariableType.PARAMETER_STORE },
          conversionBonecpUsername: { value: '/allEnvs/conversion.username', type: codebuild.BuildEnvironmentVariableType.PARAMETER_STORE },
          conversionBonecpPassword: { value: '/allEnvs/conversion.db.password', type: codebuild.BuildEnvironmentVariableType.PARAMETER_STORE },
          REPOSITORY_URI: {
            value: `/Viite/${environment}/repository_uri`,
            type: codebuild.BuildEnvironmentVariableType.PARAMETER_STORE
          },
          vvhRestApiUsername: { value: 'vvhRestApiUsername', type: codebuild.BuildEnvironmentVariableType.PARAMETER_STORE },
          vvhRestApiPassword: { value: 'vvhRestApiPassword', type: codebuild.BuildEnvironmentVariableType.PARAMETER_STORE },
          kgvEndpoint: { value: 'kgvEndpoint', type: codebuild.BuildEnvironmentVariableType.PARAMETER_STORE },
          kgvApiKey: { value: 'kgvApiKey', type: codebuild.BuildEnvironmentVariableType.PARAMETER_STORE },
        },
      },
      buildSpec: codebuild.BuildSpec.fromObject({
        version: '0.2',
        phases: {
          install: {
            'runtime-versions': {
              java: 'corretto8'
            },
            commands: [
              'export CODE_ARTIFACT_AUTH_TOKEN=$(aws codeartifact get-authorization-token --domain vayla-viite --domain-owner 783354560127 --region eu-west-1 --query authorizationToken --output text)',
              'aws codeartifact login --tool npm --repository viite_npm_packages --domain vayla-viite --domain-owner 783354560127',
              'npm config set registry https://vayla-viite-783354560127.d.codeartifact.eu-west-1.amazonaws.com/npm/viite_npm_packages/',
              'aws s3 cp s3://fi-viite-dev/sbt/sbt-0.13.18.deb /tmp/sbt-0.13.18.deb',
              'sudo dpkg -i /tmp/sbt-0.13.18.deb',
              'npm install',
              'npm install -g grunt-cli'
            ]
          },
          pre_build: {
            commands: [
              'pip install --upgrade awscli',
              '$(aws ecr get-login --no-include-email --region eu-west-1)',
              'rm -rf /etc/localtime && ln -s /usr/share/zoneinfo/Europe/Helsinki /etc/localtime'
            ]
          },
          build: {
            commands: [
              'echo Build started on `date`',
              'grunt test',
              'sbt ${1} ${2} "project viite-main" "test:run-main fi.liikennevirasto.viite.util.DataFixture flyway_migrate"',
              'sbt ${1} ${2} "project viite-main" "test:run-main fi.liikennevirasto.viite.util.DataFixture test"',
              'sbt test',
              'grunt deploy',
              'sbt clean',
              'sbt assembly',
              'docker build -t $REPOSITORY_URI:latest -f aws/fargate/Dockerfile .',
              'docker images -a'
            ]
          },
          post_build: {
            commands: [
              'echo Build completed on `date`',
              'echo "Pushing the Docker image"',
              'docker push $REPOSITORY_URI:latest',
              'echo "Image URI: $REPOSITORY_URI:latest"',
              'printf \'[{"name":"Viite","imageUri":"%s"}]\' $REPOSITORY_URI:latest > imagedefinitions.json',
              'cat imagedefinitions.json'
            ]
          }
        },
        artifacts: {
          files: [
            'imagedefinitions.json',
          ]
        }
      }),
      vpc,
      subnetSelection: vpcSubnets,
      securityGroups: [securityGroup],
      timeout: cdk.Duration.minutes(60),
      queuedTimeout: cdk.Duration.minutes(480),
    });

    // Grant permissions to CodeBuild project
    artifactBucket.grantReadWrite(buildProject.role!);
    ecrRepository.grantPullPush(buildProject.role!);

    // CodeArtifact permissions
    buildProject.addToRolePolicy(new iam.PolicyStatement({
      effect: iam.Effect.ALLOW,
      actions: [
        'codeartifact:GetAuthorizationToken',
        'codeartifact:GetRepositoryEndpoint',
        'codeartifact:ReadFromRepository'
      ],
      resources: [
        'arn:aws:codeartifact:eu-west-1:783354560127:repository/vayla-viite/viite_npm_packages',
        'arn:aws:codeartifact:eu-west-1:783354560127:repository/vayla-viite/viite_maven_packages',
        'arn:aws:codeartifact:eu-west-1:783354560127:domain/vayla-viite'
      ]
    }));

    // STS GetServiceBearerToken permission
    buildProject.addToRolePolicy(new iam.PolicyStatement({
      effect: iam.Effect.ALLOW,
      actions: ['sts:GetServiceBearerToken'],
      resources: ['*']
    }));

    // CodePipeline
    const sourceOutput = new codepipeline.Artifact();
    const buildOutput = new codepipeline.Artifact('BuildOutput');

    const pipeline = new codepipeline.Pipeline(this, 'Pipeline', {
      pipelineName: pipelineName,
      artifactBucket: artifactBucket,
      stages: [
        {
          stageName: 'Source',
          actions: [
            new codepipeline_actions.GitHubSourceAction({
              actionName: 'Source',
              owner: 'finnishtransportagency',
              repo: 'viite',
              branch: gitHubBranch,
              oauthToken: Secret.fromSecretAttributes(this, 'GitHubToken', {
                secretCompleteArn: 'arn:aws:secretsmanager:eu-west-1:783354560127:secret:github-pat-lUghBp'
              }).secretValue,
              output: sourceOutput,
            }),
          ],
        },
        {
          stageName: 'Build',
          actions: [
            new codepipeline_actions.CodeBuildAction({
              actionName: 'Build',
              project: buildProject,
              input: sourceOutput,
              outputs: [new codepipeline.Artifact('BuildOutput')],
            }),
          ],
        },
        {
          stageName: 'Deploy',
          actions: [
            new codepipeline_actions.EcsDeployAction({
              actionName: 'Deploy',
              service: ecsService,
              imageFile: buildOutput.atPath('imagedefinitions.json'),
              deploymentTimeout: Duration.minutes(20),
            }),
          ],
        },
      ],
    });

    // Grant permissions to CodePipeline
    artifactBucket.grantReadWrite(pipeline.role);

    // Grant CodeBuild permissions to CodePipeline role
    pipeline.addToRolePolicy(new iam.PolicyStatement({
      actions: [
        'codebuild:StartBuild',
        'codebuild:BatchGetBuilds'
      ],
      resources: [buildProject.projectArn]
    }));

    // Create SNS Topic for notifications
    const notificationTopic = new sns.Topic(this, 'PipelineNotificationTopic', {
      topicName: `${pipelineName}-notifications`,
      displayName: `Viite ${environment} Pipeline Notifications`
    });

    // Create a single notification rule for the pipeline
    new codestarnotifications.NotificationRule(this, 'PipelineNotification', {
      source: pipeline,
      events: [
        'codepipeline-pipeline-pipeline-execution-succeeded',
        'codepipeline-pipeline-pipeline-execution-failed'
      ],
      targets: [notificationTopic],
      detailType: codestarnotifications.DetailType.FULL,
    });



    // Output the SNS Topic ARN for external reference and subscription management
    new cdk.CfnOutput(this, 'NotificationTopicArn', {
      value: notificationTopic.topicArn,
      description: 'ARN of the SNS Topic for pipeline notifications',
    });
  }
}
