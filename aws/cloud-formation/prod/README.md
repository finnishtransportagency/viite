# Viite tuotanto, pystytys
## VPC
Luo VPC AWS-pilveen kahdella subnetilla.
Tarkista yhtenevät parametrien nimet, esim. NetworkStackName VPC:n ja CloudFormation parametreistä.

## Kloona repo koneellesi
Kloonaa viite-repo omalle koneellesi ja tee haaranvaihto postgis -haaraan

```
git clone https://github.com/finnishtransportagency/viite.git
cd viite
git checkout origin/postgis
```
## Aseta ympäristömuuttujat
Huom. ympäristömuuttujat säilyvät vain shell / cmd session ajan

*Windows Command Prompt*
```
setx AWS_DEFAULT_REGION eu-west-1
setx AWS_PROFILE centralized_service_admin
```

*Linux / macOS*
```
export AWS_DEFAULT_REGION=eu-west-1
export AWS_PROFILE=centralized_service_admin
```
## AWS CLI komennot

### Luo parametrit parameterStoreen
Parametrit luodaan tyypillä "String" ja arvolla "placeHolderValue"
```
aws cloudformation create-stack \
--stack-name [esim. viite-prod-parameter-store-entries] \
--template-body file://aws/cloud-formation/viite-parameter-store-cloudformation.yaml \
--parameters ParameterKey=Environment,ParameterValue=Prod 
```
### Päivitä parametrien arvot ja tyypit oikein
Kunkin parametrin tyypiksi vaihdetaan "SecureString" ja arvoksi asetetaan parametrin oikea arvo (X = kehitystiimiltä pyydetty arvo)
```
aws ssm put-parameter --overwrite --name /Viite/Prod/conversion.db.password --type SecureString --value X

aws ssm put-parameter --overwrite --name /Viite/Prod/authentication.admin.basic.password --type SecureString --value X

aws ssm put-parameter --overwrite --name /Viite/Prod/rds.viite.db.password --type SecureString --value X

aws ssm put-parameter --overwrite --name /Viite/Prod/vkmApiKey --type SecureString --value X
```
### Luo ECR repository
```
aws cloudformation create-stack \
--stack-name [esim. viite-prod-ecr-repository] \
--template-body file://aws/cloud-formation/viite-create-ecr-repository.yaml \
--parameters ParameterKey=Environment,ParameterValue=prod
```

### Luo task-definition

```
aws cloudformation create-stack \
--stack-name [esim. viite-prod-taskdefinition] \
--capabilities CAPABILITY_NAMED_IAM \
--template-body file://aws/cloud-formation/prod/prod-viite-create-taskdefinition-cloudformation.yaml \
--parameters ParameterKey=RepositoryURL,ParameterValue=[URL äsken luotuun ECR repositoryyn jossa kontti sijaitsee esim. 012345678910.dkr.ecr.eu-west-1.amazonaws.com]
```

### Luo Viitteen ALB-stack
```
aws cloudformation create-stack \
--stack-name [esim. viite-prod] \
--on-failure DELETE \
--template-body file://aws/cloud-formation/viite-alb_ecs.yaml \
--parameters file://aws/cloud-formation/prod/prod-parameters-viite-alb_ecs.json
```

# Viite tuotanto, päivitys

## Aseta ympäristömuuttujat
Huom. ympäristömuuttujat säilyvät vain shell / cmd session ajan

*Windows Command Prompt*
```
setx AWS_DEFAULT_REGION eu-west-1
setx AWS_PROFILE centralized_service_admin
```

*Linux / macOS*
```
export AWS_DEFAULT_REGION=eu-west-1
export AWS_PROFILE=centralized_service_admin
```
### Task definitionin päivitys
Luo uusi task definition versio
```
aws cloudformation update-stack \
--stack-name [esim. viite-prod-taskdefinition] \
--template-body file://aws/cloud-formation/prod/prod-viite-create-taskdefinition-cloudformation.yaml \
--parameters ParameterKey=RepositoryURL,ParameterValue=[URL repositoryyn jossa kontti sijaitsee esim. 012345678910.dkr.ecr.eu-west-1.amazonaws.com]
```
Ota juuri luotu task definition versio käyttöön. \
Huom.: [:VERSION] -kohdan pois jättäminen ottaa käyttöön viimeisimmän task definition version ("latest") 
```
aws ecs update-service \
--cluster Prod-Viite-ECS-Cluster-Private \
--service Prod-Viite-ECS-Service-Private \
--task-definition Prod-Viite[:VERSION] \
--force-new-deployment
```

### ALB-stackin päivitys
```
aws cloudformation update-stack \
--stack-name [esim. viite-prod] \
--capabilities CAPABILITY_NAMED_IAM \
--template-body file://aws/cloud-formation/viite-alb_ecs.yaml \
--parameters file://aws/cloud-formation/prod/prod-parameters-viite-alb_ecs.json
```

### Kontin päivitys
Kehitystiimi puskee uuden kontin ECR repositorioon jonka jälkeen service päivitetään komennolla:
```
aws ecs update-service \
--cluster Prod-Viite-ECS-Cluster-Private \
--service Prod-Viite-ECS-Service-Private \
--task-definition Prod-Viite[:VERSION] \
--force-new-deployment
```
