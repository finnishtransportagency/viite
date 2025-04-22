Building Docker Image
---------------------------
For just building the Docker image locally, you can use the script `aws-build-image.sh` located in the `aws` directory.

```bash
./aws/aws-build-image.sh
```
### Building and pushing the Docker image to AWS ECR:

If you want to build the image AND push it to AWS ECR, 
you can use the `aws-build-and-push.sh` script located in the `aws` directory.
This script handles both building the Docker image and pushing it to AWS ECR.

It pushes the image to the **Dev** environments ECR repository by default. 

To have the latest code for the image, checkout the branch you want to build the image from and pull changes.
The script does not do testing for the code so it has to be done manually if necessary.

First you need to do AWS SSO login using your configured profile:
```
aws sso login --profile vaylaapp
```

Then you must run the script from viite root directory using command:

```bash
#bash/linux
cd viite
./aws/aws-build-and-push.sh
```


