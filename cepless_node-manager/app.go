package main

import (
	"agent/manager"
	"agent/submit"
	"fmt"
	"github.com/spf13/viper"
	"os"
)

func main() {
	viper.SetConfigName("config")
	viper.AddConfigPath(".")
	err := viper.ReadInConfig() // Find and read the config file
	if err != nil { // Handle errors reading the config file
		panic(fmt.Errorf("Fatal error config file: %s \n", err))
	}

	argsWithoutProg := os.Args[1:]

	if len(argsWithoutProg) < 1 {
		printHelp()
		os.Exit(0)
	}

	var cmd = argsWithoutProg[0]
	if cmd == "manager" {
		manager.Open()
	} else if cmd == "submit" {
		if len(argsWithoutProg) < 4 {
			printSubmitHelp()
			os.Exit(0)
		}
		submit.Handle(argsWithoutProg[1], argsWithoutProg[2], argsWithoutProg[3])
	} else {
		printHelp()
		os.Exit(0)
	}
}

func printHelp() {
	fmt.Println("\nFaaS CEP Command-Line Interface")
	fmt.Println("Usage: go run app.go [COMMAND] ")
	fmt.Println("\nProvide one of the following commands to start:\n")
	fmt.Println("\tmanager: Run the node manager")
	fmt.Println("\tsubmit: Submit a new function to the registry\n")
}

func printSubmitHelp() {
	fmt.Println("\nFaaS CEP Command-Line Interface: Submit")
	fmt.Println("Usage: go run app.go submit [Function name] [Function file path] [Language]\n")
}