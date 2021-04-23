#!/usr/bin/env bash
# Author: Manisha Luthra
# Modified by: Sebastian Hennig
# Description: Sets up and execute TCEP on GENI testbed

work_dir="$(cd "$(dirname "$0")" ; pwd -P)/../"
source "$work_dir/docker-swarm.cfg"

token="undefined"

all() {
    setup
    take_down_swarm
    swarm_token=$(init_manager)
    join_workers $swarm_token
    publish
}

take_down_swarm() {
    echo "Taking down possible existing swarm"
    ssh -Tq -p $port $user@$manager 'docker swarm leave --force || exit 0;'
    for i in "${workers[@]}"
    do
        ssh -Tq -p $port $user@$i 'docker swarm leave --force || exit 0;'
    done
}

setup_instance() {
    echo "Setting up instance $1"
    ssh-keyscan -H $1 >> ~/.ssh/known_hosts
    ssh -T -p $port $user@$1 "grep -q -F '127.0.0.1 $2' /etc/hosts || sudo bash -c \"echo '127.0.0.1 $2' >> /etc/hosts\""
    ssh -T -p $port $user@$1 <<-'ENDSSH'
        mkdir -p ~/src && mkdir -p ~/logs

    if ! [ -x "$(command -v docker)" ]; then
        # Update the apt package index
        sudo apt-get update

        # Install packages to allow apt to use a repository over HTTPS
        sudo apt-get install -y \
        apt-transport-https \
        ca-certificates \
        curl \
        software-properties-common

        # Add Docker’s official GPG key
        curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo apt-key add -

        # Use the following command to set up the stable repository
        sudo add-apt-repository \
        "deb [arch=amd64] https://download.docker.com/linux/ubuntu \
        $(lsb_release -cs) \
        stable"

        # Update the apt package index
        sudo apt-get update

        # Install the latest version of Docker CE
        sudo apt-get install docker-ce -y

        # Create the docker group.
        sudo groupadd docker

        # Add your user to the docker group.
        sudo usermod -aG docker $USER
    else
        echo "Docker already installed on $1"
        sudo usermod -a -G docker $USER
    fi
ENDSSH
}

setup() {
    setup_instance $manager
    for i in "${workers[@]}"
    do
        setup_instance $i
    done
}

init_manager() {
    `ssh -T -p $port $user@$manager "sudo hostname node0 && sudo systemctl restart docker"` 2> /dev/null
    `ssh -T -p $port $user@$manager "docker swarm init --advertise-addr $manager"` 2> /dev/null
    # currently we are pushing jar file to all of the workers and building image locally on all of the workers
    # in future we should use docker registry service to create image on manager and use it as reference in worker nodes
    #ssh $user@$manager "docker service create --name registry --publish published=5000,target=5000 registry:2"
    token=`ssh -T -p $port $user@$manager 'docker swarm join-token worker -q'`
    echo $token #using this a global variable (DONT REMOVE)
}

join_workers() {
    count=0
    for i in "${workers[@]}"
    do
        count=$((count+1))
        echo "processing worker $i with hostname node$count"
        ssh -T -p $port $user@$i "docker swarm join --token $1 $manager:2377 && sudo hostname node$count && sudo systemctl restart docker"
    done
}

clear_logs() {
    ssh $user@$manager "rm -f ~/logs/*.log && rm -f ~/logs/*.csv" &
    for i in "${workers[@]}"
    do
        ssh $user@$i "rm -f ~/logs/*.log && rm -f ~/logs/*.csv" &
    done

}
publish() {
    printf "\nPulling image from registry\n"
    ssh -T -p $port $user@$manager "docker pull $registry_user/$tcep_image && docker tag $registry_user/$tcep_image tcep"
    rm -rf $work_dir/dockerbuild
    ssh -T -p $port $user@$manager "docker pull $registry_user/$gui_image && docker tag $registry_user/$gui_image tcep-gui"

    # stop already existing services
    ssh -p $port $user@$manager <<-'ENDSSH'
    if [[ $(docker service ls -q) ]]; then # only stop services if there are any running
        docker service rm $(docker service ls -q)
    fi
ENDSSH

    printf "\nBooting up new stack\n"

    ssh -p $port $user@$manager 'mkdir -p ~/logs && rm -f ~/logs/** && mkdir -p ~/src && sudo hostname node0';
    scp -P $port $work_dir/docker-stack.yml $user@$manager:~/src/docker-stack.yml
    #ssh -p $port $user@$manager 'cd ~/src && docker stack deploy --prune --with-registry-auth -c docker-stack.yml tcep';
    ssh -p $port $user@$manager 'cd ~/src && docker stack deploy --with-registry-auth -c docker-stack.yml tcep';
    clear_logs
}

rebootSwarm() {
    ##take_down_swarm
    for i in "${workers[@]}"
    do
        echo "rebooting worker $i"
        ssh -p $port $user@$i "sudo reboot" &>/dev/null
    done
    echo "rebooting manager"
    ssh -p $port $user@$manager "sudo reboot" &>/dev/null
}

# builds onejar and docker image remotely on the manager -> use when developing on non-linux OS since docker container must be built with linux
build_remote() {

    remote_sbt='false'
    ssh $user@$manager "rm -rd ~/tcep && mkdir -p ~/tcep/dockerbuild && mkdir -p ~/tcep/src && mkdir -p ~/tcep/gui && mkdir -p ~/tcep/project"
    rm src.zip && rm gui.zip
    scp $work_dir/Dockerfile $user@$manager:~/tcep/dockerbuild/
    scp $work_dir/docker-entrypoint.sh $user@$manager:~/tcep/dockerbuild/
    #zip -r -o gui.zip $work_dir/gui
    zip a -r gui.zip $work_dir/gui
    scp gui.zip $user@$manager:~/tcep && ssh $user@$manager "unzip -o ~/tcep/gui.zip -d ~/tcep"

    printf "\nLogin required to push images for localhost\n"
    ssh $user@$manager "docker login -u $registry_user -p $PW"

    if [ $remote_sbt == 'true' ]; then

        #zip -r -o src.zip $work_dir/src
        zip a -r src.zip $work_dir/src # using 7zip on windows, on linux just uncomment above

        scp src.zip $user@$manager:~/tcep && ssh $user@$manager "unzip -o ~/tcep/src.zip -d ~/tcep"
        scp $work_dir/build.sbt $user@$manager:~/tcep/build.sbt
        scp $work_dir/project/plugins.sbt $user@$manager:~/tcep/project/

        ssh -T -p $port $user@$manager <<-'ENDSSH'

        if ! [ -x "$(command -v sbt)" ]; then
            printf "\n sbt not installed on manager $manager , installing now..."
            echo "deb https://dl.bintray.com/sbt/debian /" | sudo tee -a /etc/apt/sources.list.d/sbt.list
            sudo apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 2EE0EA64E40A89B84B2DF73499E82A75642AC823
            sudo apt-get update && sudo apt-get install sbt
        fi

        if ! [ -x "$(command -v java)" ]; then
            printf "\n jre not installed on manager $manager, installing now..."
            sudo apt-get update && sudo apt-get install openjdk-8-jdk
        fi

        printf "\n Building onejar on remote... \n"
        cd ~/tcep && sbt -sbt-version 0.13.15 one-jar
        cp ~/tcep/target/scala-2.12/tcep_2.12-0.0.1-SNAPSHOT-one-jar.jar tcep/dockerbuild

ENDSSH
    else
        rm -f $work_dir/target/scala-2.12/tcep_2.12-0.0.1-SNAPSHOT-one-jar.jar
        printf "\n building onejar locally..."
        cd $work_dir && sbt -sbt-version 0.13.15 one-jar && scp $work_dir/target/scala-2.12/tcep_2.12-0.0.1-SNAPSHOT-one-jar.jar $user@$manager:~/tcep/dockerbuild/
    fi

    printf "\n Building Docker image of application and GUI \n"
    ssh $user@$manager "cd ~/tcep/dockerbuild && docker build -t tcep ~/tcep/dockerbuild && docker tag tcep $registry_user/$tcep_image"
    ssh $user@$manager "cd ~/tcep/gui && docker build -t tcep-gui . && docker tag tcep-gui $registry_user/$gui_image"
    printf "\nPushing images to registry\n"
    ssh $user@$manager "docker push $registry_user/$tcep_image && docker push $registry_user/$gui_image"

}

# Set the port variable default to 22 if not set
if [ -z $port ];
then port=22
fi

help="
Invalid usage

Publish TCEP script

Usage: ./publish_tcep.sh <COMMAND>

Available COMMAND options:
setup: Installs docker resources on every node
publish: Publish the application on the cluster and run it
take_down: Delete docker swarm cluster
all: Run all steps to publish the cluster and start the application
"

if [ -z $1 ]; then
    echo "$help"
    exit 1
fi

if [ $1 == "publish" ]; then publish
elif [ $1 == "setup" ]; then setup
elif [ $1 == "all" ]; then all
elif [ $1 == "take_down" ]; then take_down_swarm
elif [ $1 == "init_manager" ]; then init_manager
elif [ $1 == "reboot" ]; then rebootSwarm
elif [ $1 == "build_remote" ]; then build_remote
else echo "$help"
fi
