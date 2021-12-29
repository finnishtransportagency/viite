# Viite tuotanto, pystytys
## VPC
Luo VPC AWS-pilveen kahdella subnetilla.
Tarkista yhtenevät parametrien nimet, esim. NetworkStackName VPC:n ja CloudFormation parametreistä.

## Kloona repo koneellesi
1. Kloonaa viite-repo omalle koneellesi

```
git clone https://github.com/finnishtransportagency/viite.git
```
2. Tee haaranvaihto postgis -haaraan
```
git checkout origin/postgis
```
## AWS CLI komennot
### Luo parametrit parameterStoreen
Parametrit luodaan tyypillä "String" ja arvolla "placeHolderValue"

>aws cloudformation create-stack \
>   --profile [your_Väylä_profile] \
>   --region eu-west-1 \
>   --stack-name [esim. viite-prod-parameter-store-entries] \
>   --template-body file://aws/cloud-formation/prod/prod-viite-create-parameters-cloudformation.yaml 

### Päivitä parametrien arvot ja tyypit oikein
Parametrin tyypiksi vaihdetaan "SecureString" ja arvoksi asetetaan parametrin oikea arvo (kehitystiimiltä saatu)
Huom: ajettava jokaiselle parametrille erikseen!
>aws ssm put-parameter \
>   --profile [your_Väylä_profile] \
>   --region eu-west-1 \
>   --overwrite \
>   --name [parametrin nimi esim. /Viite/Prod/authentication.admin.basic.password] \
>   --type SecureString \
>   --value [kysyttäessä kehitystiimiltä]

### Luo Viitteen ALB-stack

>aws cloudformation create-stack \
>    --profile [your_Väylä_profile] \
>    --region eu-west-1 \
>    --stack-name [esim. viite-prod] \
>    --on-failure DELETE --capabilities CAPABILITY_NAMED_IAM \
>    --template-body file://aws/cloud-formation/prod/prod-viite-alb_ecs.yaml \
>    --parameters file://aws/cloud-formation/prod/prod-parameters-viite-alb_ecs.json

### Rekisteröi task-definition

>aws ecs register-task-definition \
>    --profile [your_Väylä_profile] \
>    --region eu-west-1 \
>    --cli-input-json file://aws/task-definition/prod/prod-task-definition.json

### Ota juuri rekisteröity task-definitionin versio käyttöön
Huom: [:VERSION] -kohdan pois jättäminen ottaa käyttöön viimeisimmän version ("latest") 
>aws ecs update-service \
>    --profile [your_Väylä_profile] \
>    --region eu-west-1 \
>    --cluster Prod-Viite-ECS-Cluster-Private \
>    --service Prod-Viite-ECS-Service-Private \
>    --task-definition Viite-prod[:VERSION] \
>    --force-new-deployment


# Viite tuotanto, päivitys
### Päivitä Viitteen ALB-stack

>aws cloudformation update-stack \
>    --profile [your_Väylä_profile] \
>    --region eu-west-1 \
>    --stack-name [esim. viite-prod] \
>    --on-failure DELETE --capabilities CAPABILITY_NAMED_IAM \
>    --template-body file://aws/cloud-formation/prod/prod-viite-alb_ecs.yaml \
>    --parameters file://aws/cloud-formation/prod/prod-parameters-viite-alb_ecs.json

### Rekisteröi task-definition

>aws ecs register-task-definition \
>    --profile [your_Väylä_profile] \
>    --region eu-west-1 \
>    --cli-input-json file://aws/task-definition/prod/prod-task-definition.json

### Ota juuri rekisteröity task-definitionin versio käyttöön
Huom.: [:VERSION] -kohdan pois jättäminen ottaa käyttöön viimeisimmän version ("latest") 
>aws ecs update-service \
>    --profile [your_Väylä_profile] \
>    --region eu-west-1 \
>    --cluster Prod-Viite-ECS-Cluster-Private \
>    --service Prod-Viite-ECS-Service-Private \
>    --task-definition Viite-prod[:VERSION] \
>    --force-new-deployment
