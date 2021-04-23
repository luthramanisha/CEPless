FROM golang:1.12

RUN apt-get update
RUN apt-get -y install docker
RUN mkdir /app
ADD . /app/
WORKDIR /app
RUN go build -o main .

EXPOSE 25003

ENTRYPOINT ["/app/main", "manager"]