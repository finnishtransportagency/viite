## Building And Pushing Docker Image For Initial AWS Deployment of Dev Infrastructure

When deploying the initial AWS infrastructure for the Dev environment,
you need to build and push the Docker image to AWS ECR manually before setting up the ECS services and CI/CD pipeline.

### Building Docker Image

If you want to just build the Docker image without pushing it to Viite ECR, you can use the script `aws-build-image.sh` located in the `aws` directory.
You can run this script from the viite root directory with command:
```bash
#bash/unix
cd viite
./aws/aws-build-image.sh
```
### Building and pushing the Docker image to AWS ECR:

If you want to build the image AND push it to AWS ECR, 
you can use the `aws-build-and-push.sh` script located in the `aws` directory.
This script handles both building the Docker image and pushing it to AWS ECR.

It pushes the image to the **Dev** environment ECR repository by default. 
You can change the target environment to QA by setting the `ENV` variable in the script to `qa`.

To have the latest code for the image, checkout the branch you want to build the image from and pull changes.
The script does not do testing for the code so it has to be done manually if necessary.

First you need to do AWS SSO login using your configured profile:
```bash
#bash/unix
aws sso login --profile vaylaapp
```

Then you must run the script from viite root directory using command:

```bash
#bash/unix
cd viite
./aws/aws-build-and-push.sh
```
This will build the Docker image and push it to the ECR repository for the specified environment (default is Dev).

