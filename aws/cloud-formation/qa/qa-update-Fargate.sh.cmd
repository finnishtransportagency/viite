# create
# aws cloudformation create-stack --region eu-west-1 --stack-name ViiteQAFargate --on-failure DELETE --capabilities CAPABILITY_NAMED_IAM --template-body file://qa-viite-alb_ecs.yaml --parameters file://qa-parameters-viite-alb_ecs.json --profile vaylaapp

# aws cloudformation validate-template --template-body file://qa-viite-alb_ecs.yaml --profile vaylaapp

# deploy ViiteQAFargate
# aws cloudformation deploy --stack-name ViiteQAFargate --template-file qa-viite-alb_ecs.yaml --parameter-overrides file://qa-parameters-viite-alb_ecs.json --no-execute-changeset --region eu-west-1 --profile vaylaapp

# update ViiteQAFargate
#aws cloudformation update-stack --stack-name ViiteQAFargate  --capabilities CAPABILITY_NAMED_IAM --template-body file://qa-viite-alb_ecs.yaml --parameters file://qa-parameters-viite-alb_ecs.json --profile vaylaapp


