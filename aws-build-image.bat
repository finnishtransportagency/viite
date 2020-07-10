grunt
sbt assembly
docker build -f aws\fargate\Dockerfile -t viite:latest .
