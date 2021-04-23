FROM golang:1.12

RUN mkdir /app
ADD . /app/
WORKDIR /app

RUN echo "Starting compilation"; go build -o main . && echo "Finished compilation: succedeed" || (echo "Finished compilation: failed" && exit 1)

ENTRYPOINT ["/app/main"]
EXPOSE 8080