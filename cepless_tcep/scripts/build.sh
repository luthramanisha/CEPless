#!/usr/bin/env bash
# Author: Manisha Luthra
# Modified by: Sebastian Hennig
# Description: Builds and creates the docker image of TCEP

work_dir="$(cd "$(dirname "$0")" ; pwd -P)/../"
source "${work_dir}docker-swarm.cfg"

cd $work_dir
sbt clean
sbt one-jar

if [ $? -ne 0 ]
then
    printf "\nBuild failed\n"
    exit 1
fi

printf "\nLogin required to push images for localhost\n"
docker login

mkdir $work_dir/dockerbuild
printf "\nBuilding image\n"
cp $work_dir/target/scala-2.12/tcep_2.12-0.0.1-SNAPSHOT-one-jar.jar $work_dir/dockerbuild
cp $work_dir/Dockerfile $work_dir/dockerbuild
cp $work_dir/docker-entrypoint.sh $work_dir/dockerbuild
docker build -t tcep $work_dir/dockerbuild
docker tag tcep $registry_user/$tcep_image

printf "\nBuilding GUI image\n"
cd $work_dir/gui
docker build -t tcep-gui .
docker tag tcep-gui $registry_user/$gui_image

exit 0

printf "\nPushing image to registry\n"
docker push $registry_user/$tcep_image
docker push $registry_user/$gui_image
