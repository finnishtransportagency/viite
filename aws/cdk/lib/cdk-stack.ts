import * as cdk from 'aws-cdk-lib';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as codepipeline from 'aws-cdk-lib/aws-codepipeline';
import * as codepipeline_actions from 'aws-cdk-lib/aws-codepipeline-actions';
import * as codebuild from 'aws-cdk-lib/aws-codebuild';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as kms from 'aws-cdk-lib/aws-kms';
import { CfnParameter } from 'aws-cdk-lib';
import { Construct } from 'constructs';
import {Secret} from "aws-cdk-lib/aws-secretsmanager";

export class ViiteCdkStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    // Define parameters
    const pipelineName = new CfnParameter(this, 'PipelineName', {
      type: 'String',
      default: 'viite-dev-pipeline',
      description: 'Name of the CodePipeline',
    });

    const buildProjectName = new CfnParameter(this, 'BuildProjectName', {
      type: 'String',
      default: 'viite-dev-build',
      description: 'Name of the CodeBuild project',
    });

    const gitHubOwner = new CfnParameter(this, 'GitHubOwner', {
      type: 'String',
      default: 'finnishtransportagency',
      description: 'GitHub repository owner',
    });

    const gitHubRepo = new CfnParameter(this, 'GitHubRepo', {
      type: 'String',
      default: 'viite',
      description: 'GitHub repository name',
    });

    const gitHubBranch = new CfnParameter(this, 'GitHubBranch', {
      type: 'String',
      default: 'VIITE-3167-dev-pipeline-test', // for testing purposes
      description: 'GitHub branch to use for the pipeline source',
    });

    const artifactBucketName = new CfnParameter(this, 'ArtifactBucketName', {
      type: 'String',
      default: 'fi-viite-dev',
      description: 'Name of the S3 bucket for artifacts',
    });

    const ecrRepositoryName = new CfnParameter(this, 'EcrRepositoryName', {
      type: 'String',
      default: 'viite',
      description: 'Name of the ECR repository',
    });

    const securityGroupId = new CfnParameter(this, 'SecurityGroupId', {
      type: 'AWS::EC2::SecurityGroup::Id',
      default: 'sg-0feb523b1d68c19e2',
      description: 'ID of the security group',
    });

    const kmsKeyArn = new CfnParameter(this, 'KmsKeyArn', {
      type: 'String',
      default: 'arn:aws:kms:eu-west-1:783354560127:alias/aws/s3',
      description: 'ARN of the KMS key for S3 encryption',
    });

    // Import existing resources
    const artifactBucket = s3.Bucket.fromBucketName(this, 'ExistingBucket', artifactBucketName.valueAsString);
    const ecrRepository = ecr.Repository.fromRepositoryName(this, 'ExistingECRRepo', ecrRepositoryName.valueAsString);
    const vpc = ec2.Vpc.fromLookup(this, 'ExistingVPC', { vpcId: 'vpc-017797e470d94956b' });
    const vpcSubnets = vpc.selectSubnets({
      subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS
    });
    const securityGroup = ec2.SecurityGroup.fromSecurityGroupId(this, 'ExistingSecurityGroup', securityGroupId.valueAsString);
    const encryptionKey = kms.Key.fromKeyArn(this, 'EncryptionKey', kmsKeyArn.valueAsString);

    // CodeBuild Project
    const buildProject = new codebuild.PipelineProject(this, 'BuildProject', {
      projectName: buildProjectName.valueAsString,
      environment: {
        buildImage: codebuild.LinuxBuildImage.STANDARD_5_0,
        computeType: codebuild.ComputeType.SMALL,
        privileged: true,
        environmentVariables: {
          bonecpUsername: { value: 'viite_test' },
          bonecpPassword: { value: '/dev/viite_test.db.password', type: codebuild.BuildEnvironmentVariableType.PARAMETER_STORE },
          bonecpJdbcUrl: { value: 'jdbc:postgresql://vd1bbyq5el8tjd2.c8dj2qlvf50d.eu-west-1.rds.amazonaws.com:5432/test' },
          vvhRestApiEndPoint: { value: 'https://api.vayla.fi/vvhdata/' },
          vvhServiceHost: { value: 'haproxy.vayla.fi' },
          vkmUrl: { value: 'https://api.vaylapilvi.fi' },
          JAVA_OPTS: { value: '-Xms512M -Xmx1024M -Xss1M -XX:+CMSClassUnloadingEnabled' },
          conversionBonecpJdbcUrl: { value: 'jdbc:postgresql://vd1bbyq5el8tjd2.c8dj2qlvf50d.eu-west-1.rds.amazonaws.com:5432/drkonv' },
          conversionBonecpUsername: { value: 'drkonv' },
          conversionBonecpPassword: { value: '/allEnvs/conversion.db.password', type: codebuild.BuildEnvironmentVariableType.PARAMETER_STORE },
          REPOSITORY_URI: { value: '783354560127.dkr.ecr.eu-west-1.amazonaws.com/viite' },
          vvhRestApiUsername: { value: 'svc_vvh_viite' },
          vvhRestApiPassword: { value: '/dev/vvhRestApiPassword', type: codebuild.BuildEnvironmentVariableType.PARAMETER_STORE },
          kgvEndpoint: { value: 'https://api.vaylapilvi.fi/paikkatiedot/ogc/features/collections' },
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
              'grunt test',
              'sbt ${1} ${2} "project viite-main" "test:run-main fi.liikennevirasto.viite.util.DataFixture flyway_migrate"',
              'sbt ${1} ${2} "project viite-main" "test:run-main fi.liikennevirasto.viite.util.DataFixture test"',
              'sbt test',
              'grunt deploy',
              'sbt clean',
              'sbt assembly',
              'docker build -t $REPOSITORY_URI:test -f aws/fargate/Dockerfile .',
              'docker images -a',
              //'echo "Pushing the Docker image"',
              //'docker push $REPOSITORY_URI:test',
              //'echo "Image URI: $REPOSITORY_URI:test"',
              //'aws ecs update-service --region eu-west-1 --cluster Viite-ECS-Cluster-Private --service Viite-ECS-Service-Private --force-new-deployment'
            ]
          }
        },
        artifacts: {
          'base-directory': 'dist',
          files: [
            '**/*'
          ]
        }
      }),
      vpc,
      subnetSelection: vpcSubnets,
      securityGroups: [securityGroup],
      timeout: cdk.Duration.minutes(60),
      queuedTimeout: cdk.Duration.minutes(480),
      encryptionKey,
    });

    // Grant permissions to CodeBuild project
    artifactBucket.grantReadWrite(buildProject.role!);
    ecrRepository.grantPullPush(buildProject.role!);
    buildProject.addToRolePolicy(new iam.PolicyStatement({
      actions: ['ssm:GetParameter'],
      resources: ['arn:aws:ssm:*:*:parameter/*'],
    }));

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

    const pipeline = new codepipeline.Pipeline(this, 'Pipeline', {
      pipelineName: pipelineName.valueAsString,
      artifactBucket: artifactBucket,
      stages: [
        {
          stageName: 'Source',
          actions: [
            new codepipeline_actions.GitHubSourceAction({
              actionName: 'Source',
              owner: gitHubOwner.valueAsString,
              repo: gitHubRepo.valueAsString,
              branch: gitHubBranch.valueAsString,
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
              outputs: [new codepipeline.Artifact()],
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
  }
}
