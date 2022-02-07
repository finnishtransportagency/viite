# Viite QA, pystytys
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
setx AWS_PROFILE vaylaapp
```

*Linux / macOS*
```
export AWS_DEFAULT_REGION=eu-west-1
export AWS_PROFILE=vaylaapp
```
## AWS CLI komennot

### Luo parametrit parameterStoreen
Parametrit luodaan tyypillä "String" ja arvolla "placeHolderValue"
```
aws cloudformation create-stack \
--stack-name [esim. viite-qa-parameter-store-entries] \
--template-body file://aws/cloud-formation/viite-parameter-store-cloudformation.yaml \
--parameters ParameterKey=Environment,ParameterValue=QA 
```
### Päivitä parametrien arvot ja tyypit oikein
Kunkin parametrin tyypiksi vaihdetaan "SecureString" ja arvoksi asetetaan parametrin oikea arvo (X = arvo löytyy confluencesta)
```
aws ssm put-parameter --overwrite --name /Viite/QA/conversion.db.password --type SecureString --value X

aws ssm put-parameter --overwrite --name /Viite/QA/authentication.admin.basic.password --type SecureString --value X

aws ssm put-parameter --overwrite --name /Viite/QA/rds.viite.db.password --type SecureString --value X

aws ssm put-parameter --overwrite --name /Viite/QA/vkmApiKey --type SecureString --value X
```

### Luo task-definition

```
aws cloudformation create-stack \
--stack-name [esim. viite-qa-taskdefinition] \
--capabilities CAPABILITY_NAMED_IAM \
--template-body file://aws/cloud-formation/qa/qa-viite-create-taskdefinition-cloudformation.yaml \
--parameters ParameterKey=RepositoryURL,ParameterValue=[URL repositoryyn jossa kontti sijaitsee esim. 012345678910.dkr.ecr.eu-west-1.amazonaws.com]
```

### Luo Viitteen ALB-stack
```
aws cloudformation create-stack \
--stack-name [esim. viite-qa] \
--on-failure DELETE \
--template-body file://aws/cloud-formation/viite-alb_ecs.yaml \
--parameters file://aws/cloud-formation/qa/qa-parameters-viite-alb_ecs.json
```

### Luo CNAME record-stack
```
aws cloudformation create-stack \
--stack-name [esim. viite-qa-cname-to-route53] \
--template-body file://aws/cloud-formation/qa/qa-viite-create-cname-record-to-route53.yaml \
--parameters ParameterKey=CNameRecord,ParameterValue=viitetest ParameterKey=LoadBalancerDNSName,ParameterValue=[Kuormantasaajan DNS nimi esim. internal-viite-Priva-ABCDEFGHIJKL-012345678910.eu-west-1.elb.amazonaws.com]
```

# Viite QA, päivitys

## Aseta ympäristömuuttujat
Huom. ympäristömuuttujat säilyvät vain shell / cmd session ajan

*Windows Command Prompt*
```
setx AWS_DEFAULT_REGION eu-west-1
setx AWS_PROFILE vaylaapp
```

*Linux / macOS*
```
export AWS_DEFAULT_REGION=eu-west-1
export AWS_PROFILE= vaylaapp
```
### Task definitionin päivitys
Luo uusi task definition versio
```
aws cloudformation update-stack \
--stack-name [esim. viite-qa-taskdefinition] \
--template-body file://aws/cloud-formation/qa/qa-viite-create-taskdefinition-cloudformation.yaml \
--parameters ParameterKey=RepositoryURL,ParameterValue=[URL repositoryyn jossa kontti sijaitsee esim. 012345678910.dkr.ecr.eu-west-1.amazonaws.com]
```
Ota juuri luotu task definition versio käyttöön. \
Huom.: [:VERSION] -kohdan pois jättäminen ottaa käyttöön viimeisimmän task definition version ("latest") 
```
aws ecs update-service \
--cluster QA-viite-test-ECS-Cluster-Private \
--service QA-viite-test-ECS-Service-Private \
--task-definition QA-viite-test[:VERSION] \
--force-new-deployment
```

### ALB-stackin päivitys
```
aws cloudformation update-stack \
--stack-name [esim. viite-qa] \
--capabilities CAPABILITY_NAMED_IAM \
--template-body file://aws/cloud-formation/viite-alb_ecs.yaml \
--parameters file://aws/cloud-formation/qa/qa-parameters-viite-alb_ecs.json
```

### Kontin päivitys
Aseta Devtest:latest-kontti -> QA:latest-kontiksi
```
aws-deploy-qa.sh
```
