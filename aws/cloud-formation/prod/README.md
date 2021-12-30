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
set AWS_DEFAULT_REGION eu-west-1
set AWS_PROFILE centralized_service_admin
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
--template-body file://aws/cloud-formation/prod/prod-viite-create-parameters-cloudformation.yaml 
```
### Päivitä parametrien arvot ja tyypit oikein
Kunkin parametrin tyypiksi vaihdetaan "SecureString" ja arvoksi asetetaan parametrin oikea arvo (X = kehitystiimiltä pyydetty arvo)
```
aws ssm put-parameter --overwrite --name /Viite/Prod/conversion.db.password --type SecureString --value X

aws ssm put-parameter --overwrite --name /Viite/Prod/authentication.admin.basic.password --type SecureString --value X

aws ssm put-parameter --overwrite --name /Viite/Prod/rds.viite.db.password --type SecureString --value X

aws ssm put-parameter --overwrite --name /Viite/Prod/vkmApiKey --type SecureString --value X
```

### Luo Viitteen ALB-stack
```
aws cloudformation create-stack \
--stack-name [esim. viite-prod] \
--on-failure DELETE --capabilities CAPABILITY_NAMED_IAM \
--template-body file://aws/cloud-formation/prod/prod-viite-alb_ecs.yaml \
--parameters file://aws/cloud-formation/prod/prod-parameters-viite-alb_ecs.json
```
### Rekisteröi task-definition
```
aws ecs register-task-definition --cli-input-json file://aws/task-definition/prod/prod-task-definition.json
```

### Ota juuri rekisteröity task-definitionin versio käyttöön
Huom: [:VERSION] -kohdan pois jättäminen ottaa käyttöön viimeisimmän version ("latest") 
```
aws ecs update-service \
--cluster Prod-Viite-ECS-Cluster-Private \
--service Prod-Viite-ECS-Service-Private \
--task-definition Viite-prod[:VERSION] \
--force-new-deployment
```

# Viite tuotanto, päivitys

## Aseta ympäristömuuttujat
Huom. ympäristömuuttujat säilyvät vain shell / cmd session ajan

*Windows Command Prompt*
```
set AWS_DEFAULT_REGION eu-west-1
set AWS_PROFILE centralized_service_admin
```

*Linux / macOS*
```
export AWS_DEFAULT_REGION=eu-west-1
export AWS_PROFILE=centralized_service_admin
```

### Päivitä Viitteen ALB-stack
```
aws cloudformation update-stack \
--stack-name [esim. viite-prod] \
--on-failure DELETE --capabilities CAPABILITY_NAMED_IAM \
--template-body file://aws/cloud-formation/prod/prod-viite-alb_ecs.yaml \
--parameters file://aws/cloud-formation/prod/prod-parameters-viite-alb_ecs.json
```
### Rekisteröi task-definition
```
aws ecs register-task-definition --cli-input-json file://aws/task-definition/prod/prod-task-definition.json
```
### Ota juuri rekisteröity task-definitionin versio käyttöön
Huom.: [:VERSION] -kohdan pois jättäminen ottaa käyttöön viimeisimmän version ("latest") 
```
aws ecs update-service \
--cluster Prod-Viite-ECS-Cluster-Private \
--service Prod-Viite-ECS-Service-Private \
--task-definition Viite-prod[:VERSION] \
--force-new-deployment
```
