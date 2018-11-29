docker volume inspect viite-ivy-releases
if [$? -eq 1];
then
  docker volume create viite-ivy-releases
fi