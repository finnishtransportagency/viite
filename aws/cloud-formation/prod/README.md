# Viite tuotanto, pystytys


### VPC
Luo VPC AWS-pilveen kahdella subnetilla.
Tarkista yhtenevät parametrien nimet, esim. NetworkStackName VPC:n ja CloudFormation parametreistä.

### parameterStore-entryt
Lisää parameterStore-entryt.
Skripti: ViiteProd_parameterStoreEntries.sh. Conflussa, kehitystiimiltä.

### Luo Viitteen ALB-stack
Viitatut tiedostot hakemistossa ***[Viitteen git-juuri]***/aws/cloud-formation/prod

>aws cloudformation create-stack \
>    --profile [your_Viite_profile] \
>    --region eu-west-1 \
>    --stack-name [name_for_the_stack] \
>    --on-failure DELETE --capabilities CAPABILITY_NAMED_IAM \
>    --template-body file:///prod-viite-alb_ecs.yaml \
>    --parameters file://prod-parameters-viite-alb_ecs.json

### Rekisteröi task-definition
Viitatut tiedostot hakemistossa ***[Viitteen git-juuri]***/aws/task-definition/prod

>aws ecs register-task-definition \
>    --profile [your_Viite_profile] \
>    --region eu-west-1 \
>    --cli-input-json file:///prod-task-definition.json

### Ota juuri rekisteröity task-definitionin versio käyttöön
Huom.: [:VERSION] -kohdan pois jättäminen ottaa käyttään viimeisimmän ("latest") 
>aws ecs update-service \
>    --profile [your_Viite_profile] \
>    --region eu-west-1 \
>    --cluster Prod-Viite-ECS-Cluster-Private \
>    --service Prod-Viite-ECS-Service-Private \
>    --task-definition Viite-prod[:VERSION] \
>    --force-new-deployment