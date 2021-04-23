package main

import "log"
import "time"

func makeTimestamp() int64 {
	return time.Now().UnixNano() / int64(time.Millisecond)
}


func ReceiveUnary(item map[string]interface{}) map[string]interface{} {
	// Custom function code
	log.Printf("Received new item %v %v", item["data"], makeTimestamp())
	result := make(map[string]interface{})
	result["data"] = item["data"]
	log.Printf("Sending returned item %v", result["data"])
	return result
}
