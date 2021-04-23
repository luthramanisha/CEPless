cd $HOME/ba-sebastian/flink
docker-compose down
docker container stop $(docker container ls -a -q)
docker container rm $(docker container ls -a -q)
screen -XS test quit

