package main

import (
	"fmt"
	"github.com/go-redis/redis"
	"log"
	"net/http"
	"os"
	"time"
)

var redisClient = redis.NewClient(&redis.Options{
	Addr:     "redis:6379",
	Password: "", // no password set
	DB:       0,  // use default DB
})

var listen = false
var addrIn = os.Getenv("ADDR_IN")
var addrOut = os.Getenv("ADDR_OUT")

func start(w http.ResponseWriter, r *http.Request) {
	log.Printf("Listening on channel " + addrIn)
	listen = true
	listenChannel(addrIn)
}

func send(item string) {
	log.Printf("Sending item %v into queue %v", item, addrOut);
	if err := redisClient.RPush(addrOut, item).Err(); err != nil {
		log.Fatalf("Failed to put item into queue %v", err);
	}
}

func listenChannel(channelName string) {
	log.Printf("Using channel %v", channelName)
	for {
		if !listen {
			break
		}
		task, err := redisClient.BLPop(time.Duration(1 * time.Minute), channelName).Result()
		log.Printf("Got task %v", task)
		if err != nil {
			log.Fatalf("Failed to get task from queue: %v\n", err)
			break
		} else if task == nil || len(task) == 0 {
			log.Println("No event in queue...")
			continue
		}

		if len(task) < 2 {
			log.Println("No event in queue...1")
			continue
		}
		fmt.Printf("%v", task)
		processItem(task[1])
	}
}

func processItem(task string) {
	var data = make(map[string]interface{})
	data["data"] = task
	result := ReceiveUnary(data)
	if len(result) != 0 {
		send(result["data"].(string))
	}
}

func stop(w http.ResponseWriter, r *http.Request) {
	print("Stop processing items")
	listen = false
}

func main() {
	if len(addrIn) == 0 {
	 	panic("Input address not defined but is required... Stopping...")
	}
	if len(addrOut) == 0 {
	// 	panic("Output address not defined but is required... Stopping...")
	}

	log.Printf("AddrIn is " + addrIn)

	http.HandleFunc("/start", start)
	http.HandleFunc("/stop", stop)

	start(nil, nil)

	if err := http.ListenAndServe(":25002", nil); err != nil {
		panic(err)
	}
}




