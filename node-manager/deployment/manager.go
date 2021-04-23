package deployment

import (
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"strconv"
	"time"

	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/api/types/network"
	"github.com/spf13/viper"

	"github.com/docker/docker/api/types"
	"github.com/docker/docker/client"
)

var networkName = "node-manager-net"

func getClient() *client.Client {
	cli, err := client.NewClient("unix:///var/run/docker.sock", "", nil, make(map[string]string))
	if err != nil {
		panic(err)
	}
	return cli
}

// Initialize the deployment manager by checking if the node manager network exists
//
// The network is required in order for the custom operators (containers) to be able to communicate with the redis of the docker swarm
func Init() {
	log.Println("Checking if node manager network exists...")

	cli := getClient()
	networks, err := cli.NetworkList(context.Background(), types.NetworkListOptions{})
	if err != nil {
		panic(err)
	}
	found := false
	for key := range networks {
		network := networks[key]
		if network.Name == networkName {
			found = true
		}
	}
	if found {
		log.Println("Node manager network already exists. Initialization finished")
		return
	}
	log.Printf("Creating node manager network (%v)...", networkName)
	resp, err := cli.NetworkCreate(context.Background(), networkName, types.NetworkCreate{})
	if err != nil {
		panic(err)
	}
	log.Printf("\nNode manager network created. Initialization finished (%v)", resp.ID)
}

func checkOperatorsRunning(cli *client.Client, operatorRequestIdentifier string) error {
	containers, err := cli.ContainerList(context.Background(), types.ContainerListOptions{
		All: true,
	})
	if err != nil {
		panic(err)
	}
	found := false
	for cKey := range containers {
		cont := containers[cKey]

		for nKey := range cont.Names {
			if cont.Names[nKey] == "/"+operatorRequestIdentifier {
				found = true
				break
			}
		}
		if found {
			cli.ContainerRemove(context.Background(), cont.ID, types.ContainerRemoveOptions{Force: true})
		}
	}
	return nil
}

func loginRegistry() error {
	/*username := viper.GetString("registry.user")

	body, err := cli.RegistryLogin(context.Background(), types.AuthConfig{
		Username: username,
		Password: viper.GetString("registry.password"),
	})
	if err != nil || body.Status == "failed" {
		return errors.New("registry login wrong")
	}
	*/
	return nil
}

func checkImageExists(cli *client.Client, operatorTag string) (string, error) {
	images, err := cli.ImageList(context.Background(), types.ImageListOptions{All: true})
	if err != nil {
		panic(err)
	}
	var deploymentImageId = ""
	for key := range images {
		image := images[key]
		for tagKey := range image.RepoTags {
			if image.RepoTags[tagKey] == (operatorTag + ":latest") {
				deploymentImageId = image.ID
				break
			}
		}
	}

	if len(deploymentImageId) != 0 {
		fmt.Println(deploymentImageId)
		return deploymentImageId, nil
	} else {
		fmt.Println("Container not found!!")
		return "", errors.New("Container not found")
	}
}

func startOperator(cli *client.Client, imageId string, operatorIdentifier string, addrIn string, addrOut string) (container.ContainerCreateCreatedBody, error) {
	networkingConfig := make(map[string]*network.EndpointSettings)
	networkingConfig[networkName] = &network.EndpointSettings{}
	resp, err := cli.ContainerCreate(context.Background(), &container.Config{
		Env: []string{
			"ADDR_IN=" + addrIn,
			"ADDR_OUT=" + addrOut,
			"DB_TYPE=redis",
			"OUT_BATCH_SIZE=100",
			"IN_BATCH_SIZE=100",
			"FLUSH_INTERVAL=1",
			"DB_HOST=redis",
			"BACK_OFF=1",
		},
		Image: imageId,
	}, nil, &network.NetworkingConfig{
		EndpointsConfig: networkingConfig,
	}, operatorIdentifier)
	if err != nil {
		panic(err)
	}

	if err := cli.ContainerStart(context.Background(), resp.ID, types.ContainerStartOptions{}); err != nil {
		panic(err)
	}
	return resp, nil
}

func stopOperator(cli *client.Client, containerName string) error {
	containers, err := cli.ContainerList(context.Background(), types.ContainerListOptions{})
	if err != nil {
		return err
	}
	for key := range containers {
		c := containers[key]
		// fmt.Println(c)
		for id := range c.Names {
			name := c.Names[id]
			if name == "/" + containerName {
				timeout := 0 * time.Millisecond
				cli.ContainerStop(context.Background(), c.ID, &timeout)
				cli.ContainerRemove(context.Background(), c.ID, types.ContainerRemoveOptions{})
			}
		}
	}
	return nil
}

// Deploy the operator with the given name by pulling the requested image and running the docker container
func DeployNewOperator(operatorName string, operatorRequestIdentifier string, addrIn string, addrOut string) error {
	operatorUpdateName := os.Getenv("OPERATOR_UPDATE_NAME")
	operatorUpdateTimeout := os.Getenv("OPERATOR_UPDATE_TIMEOUT")
	cli := getClient()

	err := checkOperatorsRunning(cli, operatorRequestIdentifier)
	if err != nil {
		panic(err)
	}

	username := viper.GetString("registry.user")
	err = loginRegistry()
	if err != nil {
		panic(err)
	}
	operatorTag := username + "/" + operatorName
	/*out, err := cli.ImagePull(context.Background(), "docker.io/" + operatorTag, types.ImagePullOptions{ All: true })
	if err != nil || out == nil {
		panic(err)
	}
	errs := helper.DetectErrorMessage(out)
	if errs != nil {
		panic(err)
	}*/

	deploymentImageId, err := checkImageExists(cli, operatorTag)
	if err != nil {
		panic(err)
	}

	resp, err := startOperator(cli, deploymentImageId, operatorRequestIdentifier, addrIn, addrOut)

	log.Println(resp.ID)
	fmt.Println("Started operator with requestIdentifier " + operatorRequestIdentifier)

	updateTimeout, err := strconv.ParseInt(operatorUpdateTimeout, 10, 64)
	if updateTimeout > 0 {
		fmt.Printf("Scheduling operator update in %d seconds", updateTimeout)
		time.AfterFunc(time.Duration(updateTimeout) * time.Second, func() {
			fmt.Println("Performing operator update")
			UpdateOperator(operatorRequestIdentifier, operatorUpdateName, operatorRequestIdentifier + "-updated", addrIn, addrOut)
		})
	} else {
		fmt.Println("No operator update scheduled")
	}

	return nil
}

func UpdateOperator(oldOperatorIdentifier string, newOperatorName string, newOperatorIdentifier string, addrIn string, addrOut string) error {
	cli := getClient()
	err := loginRegistry()
	username := viper.GetString("registry.user")
	if err != nil {
		panic(err)
	}
	operatorTag := username + "/" + newOperatorName
	deploymentImageId, err := checkImageExists(cli, operatorTag)
	if err != nil {
		panic(err)
	}

	resp, err := startOperator(cli, deploymentImageId, newOperatorIdentifier, addrIn, addrOut)

	// Add delay here so the new operator is already started when the old one is stopped
	time.Sleep(5 * time.Second)

	err = stopOperator(cli, oldOperatorIdentifier)
	if err != nil {
		panic(err)
	}

	log.Println(resp.ID)
	fmt.Println("Updated operator with requestIdentifier " + newOperatorIdentifier)
	return nil
}
