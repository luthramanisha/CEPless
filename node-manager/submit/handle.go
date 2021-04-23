package submit

import (
	"agent/helper"
	"archive/tar"
	"bufio"
	"compress/gzip"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"io/ioutil"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/docker/docker/api/types"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/api/types/network"
	"github.com/docker/docker/client"
	"github.com/docker/go-connections/nat"
	"github.com/jhoonb/archivex"
	"github.com/spf13/viper"

	"github.com/acarl005/stripansi"
)

// (begin) added by Matheus
type streamLine struct {
	Stream string
}

type CompilationError struct {
	Err error
	Msg string
	Log []byte
}

func (e *CompilationError) Error() string {
	return fmt.Sprintf("%v: %v", e.Msg, e.Err.Error())
}

func check(e error) {
	if e != nil {
		log.Println(e)
		return
	}
}

// build operator image
func BuildOperator(operatorName string, filePath string, language string) ([]byte, error) {
	buildLog := []byte("")       // stores the build log
	compilationLog := []byte("") // stores the compilation log

	log.Printf("Building operator %s: language=%s, filePath=%s", operatorName, language, filePath)

	fi, err := os.Stat(filePath)
	if err != nil {
		fmt.Println(err)
		return buildLog, err
	}
	if !fi.Mode().IsRegular() {
		fmt.Println("Please specify a valid function file")
		return buildLog, err
	}
	file, err := os.Open(filePath)
	if err != nil {
		fmt.Println(err)
		return buildLog, err
	}
	defer file.Close()

	var path string               // path to the build environment folder
	var operatorPath string       // path to the operator source code
	var errorStart *regexp.Regexp // matches the beggining of the outputed compilation errors

	switch language {
	case "go":
		path = "operators/go-template"
		operatorPath = "operators/go-template/operator.go"
		errorStart = regexp.MustCompile("# example-operator")

	case "java": // added by Minh
		path = "operators/java-template"
		operatorPath = "operators/java-template/src/main/java/operator/Operator.java"
		errorStart = regexp.MustCompile("COMPILATION ERROR")

	case "python": // added by Minh
		path = "operators/python-template"
		operatorPath = "operators/python-template/op.py"
		errorStart = regexp.MustCompile("(?s)(?:Compiling './op.py')(.+)")

	case "cpp":
		path = "operators/cpp-template"
		operatorPath = "operators/cpp-template/operator.cpp"

	default:
		log.Println("Unknown language")
		os.Exit(1)
	}

	err = os.Remove(operatorPath)
	if err != nil && !os.IsNotExist(err) {
		return buildLog, err
	}
	destination, err := os.Create(operatorPath)
	if err != nil {
		return buildLog, err
	}
	defer func() {
		err = os.Remove(operatorPath)
		if err != nil && !os.IsNotExist(err) {
			check(err)
		}
		destination.Close()
	}()

	// copies operator into template folder
	nBytes, err := io.Copy(destination, file)
	if err != nil && nBytes == 0 {
		log.Println(err)
		return buildLog, err
	}

	cli, err := client.NewEnvClient()
	if err != nil {
		return buildLog, err
	}

	tar := new(archivex.TarFile)
	tar.Create("/tmp/out.tar")
	tar.AddAll(path, false)
	tar.Close()
	dockerBuildContext, err := os.Open("/tmp/out.tar")
	if err != nil {
		return buildLog, err
	}
	defer dockerBuildContext.Close()

	options := types.ImageBuildOptions{
		Tags:           []string{operatorName},
		SuppressOutput: false,
	}

	// build image
	log.Printf("Building image for %s", operatorName)
	buildResponse, err := cli.ImageBuild(context.Background(), dockerBuildContext, options)
	if err != nil {
		return buildLog, err
	}

	// parse the build response
	response, err := ioutil.ReadAll(buildResponse.Body)
	check(err)
	compilationFinish := regexp.MustCompile(`(?:^Finished compilation: )(?P<status>[a-z]+)`)
	var stream streamLine
	var compilationStatus string
	errorStarted := false
	scanner := bufio.NewScanner(strings.NewReader(string(response)))
	for scanner.Scan() {
		err = json.Unmarshal([]byte(scanner.Text()), &stream)
		check(err)
		line := stripansi.Strip(stream.Stream) // remove output color
		fmt.Printf("%s", stream.Stream)
		buildLog = append(buildLog, line...) // write line to buildLog
		if errorStart.MatchString(line) {    // detects the start of the compilation step
			errorStarted = true
		}
		if errorStarted {
			if compilationFinish.MatchString(line) {
				errorStarted = false
				compilationStatus = compilationFinish.FindStringSubmatch(line)[1]
				continue
			}
			compilationLog = append(compilationLog, line...)
		}
	}

	if compilationStatus == "failed" {
		// if compilation failed return CompilationError
		return buildLog, &CompilationError{err, "Compilation error", compilationLog}
	}
	return buildLog, nil
}

// submit image to registry
func SubmitOperator(operatorName string) {
	log.Printf("Submitting operator %s", operatorName)

	username := viper.GetString("registry.user")
	auth := types.AuthConfig{
		Username: username,
		Password: viper.GetString("registry.password"),
	}
	authBytes, _ := json.Marshal(auth)
	authBase64 := base64.URLEncoding.EncodeToString(authBytes)

	cli, err := client.NewEnvClient()
	check(err)

	_, err = cli.RegistryLogin(context.Background(), auth)
	check(err)

	name := "docker.io/" + username + "/" + operatorName + ":latest"

	customImage, raw, err := cli.ImageInspectWithRaw(context.Background(), operatorName)
	if err != nil || raw == nil {
		log.Println(err)
		return
	}
	err = cli.ImageTag(context.Background(), customImage.ID, name)
	check(err)

	// https://github.com/moby/moby/issues/10983
	out, err := cli.ImagePush(context.Background(), name, types.ImagePushOptions{
		All:          true,
		RegistryAuth: authBase64,
	})
	if err != nil || out == nil {
		log.Println(err)
		return
	}
	defer out.Close()
	errs := helper.DetectErrorMessage(out)
	if errs != nil {
		log.Println(errs)
		return
	}

	switch os.Getenv("EXECUTION") {
	case "tcep":
		log.Println("Restarting containers")
		containerList := []string{
			"tcep_SanitizerSensor_1",
			"tcep_simulator_1",
			"tcep_app3_1",
		}
		err = stopContainers(context.Background(), cli, containerList)
		check(err)

		commitResp, err := cli.ContainerCommit(context.Background(), "tcep_simulator_1", types.ContainerCommitOptions{
			Changes: []string{"ENV CUSTOM_OPERATOR=" + operatorName},
		})
		err = cli.ContainerRemove(context.Background(), "tcep_simulator_1", types.ContainerRemoveOptions{})
		check(err)
		_, err = cli.ContainerCreate(context.Background(), &container.Config{
			Image: commitResp.ID,
		}, &container.HostConfig{
			Binds:       []string{os.Getenv("HOST_LOG_PATH") + ":/app/logs"},
			NetworkMode: "node-manager-net",
			PortBindings: nat.PortMap{
				"2202/tcp":  []nat.PortBinding{nat.PortBinding{"", "2202"}},
				"25001/tcp": []nat.PortBinding{nat.PortBinding{"", "25001"}},
			},
			Privileged: true,
			IpcMode:    "shareable",
		}, &network.NetworkingConfig{
			map[string]*network.EndpointSettings{
				"tcep_main": &network.EndpointSettings{
					Aliases: []string{"simulator"},
				},
			},
		}, "tcep_simulator_1")
		check(err)
		cli.NetworkConnect(context.Background(), "tcep_main", "tcep_simulator_1", nil)

		commitResp, err = cli.ContainerCommit(context.Background(), "tcep_SanitizerSensor_1", types.ContainerCommitOptions{
			Changes: []string{"ENV CUSTOM_OPERATOR=" + operatorName},
		})
		err = cli.ContainerRemove(context.Background(), "tcep_SanitizerSensor_1", types.ContainerRemoveOptions{})
		check(err)
		_, err = cli.ContainerCreate(context.Background(), &container.Config{
			Image: commitResp.ID,
		}, &container.HostConfig{
			Binds:       []string{os.Getenv("HOST_LOG_PATH") + ":/app/logs"},
			NetworkMode: "node-manager-net",
			PortBindings: nat.PortMap{
				"3301/tcp": []nat.PortBinding{nat.PortBinding{"", "3301"}},
			},
			Privileged: true,
			IpcMode:    "shareable",
		}, &network.NetworkingConfig{
			map[string]*network.EndpointSettings{
				"tcep_main": &network.EndpointSettings{
					Aliases: []string{"SanitizerSensor"},
				},
			},
		}, "tcep_SanitizerSensor_1")
		check(err)
		cli.NetworkConnect(context.Background(), "tcep_main", "tcep_SanitizerSensor_1", nil)

		err = startContainers(context.Background(), cli, containerList)
		check(err)

	case "flink":
		// do nothing (custom operator name can be handled by flink-test)
	default:
		// do nothing
	}

	log.Printf("%s successfully submitted", operatorName)
}

func stopContainers(ctx context.Context, cli *client.Client, containerList []string) error {
	for _, containerName := range containerList {
		err := cli.ContainerStop(ctx, containerName, nil)
		if err != nil {
			return err
		}
	}
	return nil
}

func startContainers(ctx context.Context, cli *client.Client, containerList []string) error {
	for _, containerName := range containerList {
		err := cli.ContainerStart(ctx, containerName, types.ContainerStartOptions{})
		if err != nil {
			return err
		}
	}
	return nil
}

// (end) added by Matheus

// function handle was changed, but it was splitted between the buildOperator and submitOperator function,
// so it is no longer used by the HTTP server
func Handle(operatorName string, filePath string, language string) {
	log.Printf("Submitting operator %s: language=%s, filePath=%s", operatorName, language, filePath)

	fi, err := os.Stat(filePath)
	if err != nil {
		fmt.Println(err)
		return
	}
	if !fi.Mode().IsRegular() {
		fmt.Println("Please specify a valid function file")
		return
	}
	file, err := os.Open(filePath)
	if err != nil {
		fmt.Println(err)
		return
	}
	defer file.Close()

	var path string

	if language == "go" {
		err = os.Remove("operators/go-template/operator.go")
		if err != nil && !os.IsNotExist(err) {
			check(err)
		}
		destination, err := os.Create("operators/go-template/operator.go")
		check(err)
		defer func() {
			err = os.Remove("operators/go-template/operator.go")
			if err != nil && !os.IsNotExist(err) {
				check(err)
			}
			destination.Close()
		}()
		// copies operator into template folder
		nBytes, err := io.Copy(destination, file)
		if err != nil && nBytes == 0 {
			log.Println(err)
			return
		}

		path = "operators/go-template"
	} else if language == "java" {
		err = os.Remove("operators/java-template/src/main/java/operator/Operator.java")
		if err != nil && !os.IsNotExist(err) {
			check(err)
		}
		destination, err := os.Create("operators/java-template/src/main/java/operator/Operator.java")
		check(err)
		defer func() {
			err = os.Remove("operators/java-template/src/main/java/operator/Operator.java")
			if err != nil && !os.IsNotExist(err) {
				check(err)
			}
			destination.Close()
		}()
		// copies operator into template folder
		nBytes, err := io.Copy(destination, file)
		if err != nil && nBytes == 0 {
			log.Println(err)
			return
		}
		path = "operators/java-template"

		// builds jar
		build, err := exec.Command("operators/java-template/build.sh").Output()
		if err != nil {
			log.Println("Build error: " + fmt.Sprint(err))
		} else {
			log.Println("Build successful")
			log.Println(build)
		}
	} else if language == "python" {
		err = os.Remove("operators/python-template/operator.py")
		if err != nil && !os.IsNotExist(err) {
			check(err)
		}
		destination, err := os.Create("operators/python-template/operator.py")
		check(err)
		defer func() {
			err = os.Remove("operators/python-template/operator.py")
			if err != nil && !os.IsNotExist(err) {
				check(err)
			}
			destination.Close()
		}()
		// copies operator into template folder
		nBytes, err := io.Copy(destination, file)
		if err != nil && nBytes == 0 {
			log.Println(err)
			return
		}
		path = "operators/python-template"
	} else if language == "cpp" {
		err = os.Remove("operators/cpp-template/cpp_template/operator.cpp")
		if err != nil && !os.IsNotExist(err) {
			check(err)
		}
		destination, err := os.Create("operators/cpp-template/cpp_template/operator.cpp")
		check(err)
		defer func() {
			err = os.Remove("operators/cpp-template/cpp_template/operator.cpp")
			if err != nil && !os.IsNotExist(err) {
				check(err)
			}
			destination.Close()
		}()
		// copies operator into template folder
		nBytes, err := io.Copy(destination, file)
		if err != nil && nBytes == 0 {
			log.Println(err)
			return
		}

		path = "operators/cpp-template"
	} else {
		log.Println("Unknown language")
		os.Exit(1)
	}

	cli, err := client.NewEnvClient()
	if err != nil {
		panic(err)
	}

	tar := new(archivex.TarFile)
	tar.Create("/tmp/out.tar")
	tar.AddAll(path, false)
	tar.Close()
	dockerBuildContext, err := os.Open("/tmp/out.tar")
	check(err)
	defer dockerBuildContext.Close()

	options := types.ImageBuildOptions{
		Tags:           []string{operatorName},
		SuppressOutput: false,
	}
	log.Printf("Building image for %s", operatorName)
	buildResponse, err := cli.ImageBuild(context.Background(), dockerBuildContext, options)
	if err != nil {
		log.Println(err)
		return
	}
	response, err := ioutil.ReadAll(buildResponse.Body)
	check(err)

	var stream streamLine
	scanner := bufio.NewScanner(strings.NewReader(string(response)))
	for scanner.Scan() {
		err = json.Unmarshal([]byte(scanner.Text()), &stream)
		check(err)
		fmt.Printf(stream.Stream)
	}

	username := viper.GetString("registry.user")
	auth := types.AuthConfig{
		Username: username,
		Password: viper.GetString("registry.password"),
	}
	authBytes, _ := json.Marshal(auth)
	authBase64 := base64.URLEncoding.EncodeToString(authBytes)

	body, err := cli.RegistryLogin(context.Background(), auth)
	check(err)
	log.Println(body)

	name := "docker.io/" + username + "/" + operatorName + ":latest"
	// fmt.Println(name)
	// fmt.Println(authBase64)

	customImage, raw, err := cli.ImageInspectWithRaw(context.Background(), operatorName)
	if err != nil || raw == nil {
		log.Println(err)
		return
	}
	// log.Println(customImage)
	err = cli.ImageTag(context.Background(), customImage.ID, name)
	check(err)

	// https://github.com/moby/moby/issues/10983
	out, err := cli.ImagePush(context.Background(), name, types.ImagePushOptions{
		All:          true,
		RegistryAuth: authBase64,
	})
	if err != nil || out == nil {
		log.Println(err)
		return
	}
	defer out.Close()
	errs := helper.DetectErrorMessage(out)
	if errs != nil {
		log.Println(errs)
		return
	}

	switch os.Getenv("EXECUTION") {
	case "tcep":
		containerList := []string{
			"tcep_SanitizerSensor_1",
			"tcep_simulator_1",
		}
		err = stopContainers(context.Background(), cli, containerList)
		check(err)

		commitResp, err := cli.ContainerCommit(context.Background(), "tcep_simulator_1", types.ContainerCommitOptions{
			Changes: []string{"ENV CUSTOM_OPERATOR=" + operatorName},
		})
		err = cli.ContainerRemove(context.Background(), "tcep_simulator_1", types.ContainerRemoveOptions{})
		check(err)
		_, err = cli.ContainerCreate(context.Background(), &container.Config{
			Image: commitResp.ID,
		}, &container.HostConfig{
			Binds:       []string{os.Getenv("HOST_LOG_PATH") + ":/app/logs"},
			NetworkMode: "node-manager-net",
			PortBindings: nat.PortMap{
				"2202/tcp":  []nat.PortBinding{nat.PortBinding{"", "2202"}},
				"25001/tcp": []nat.PortBinding{nat.PortBinding{"", "25001"}},
			},
			Privileged: true,
			IpcMode:    "shareable",
		}, &network.NetworkingConfig{
			map[string]*network.EndpointSettings{
				"tcep_main": &network.EndpointSettings{
					Aliases: []string{"simulator"},
				},
			},
		}, "tcep_simulator_1")
		check(err)
		cli.NetworkConnect(context.Background(), "tcep_main", "tcep_simulator_1", nil)

		commitResp, err = cli.ContainerCommit(context.Background(), "tcep_SanitizerSensor_1", types.ContainerCommitOptions{
			Changes: []string{"ENV CUSTOM_OPERATOR=" + operatorName},
		})
		err = cli.ContainerRemove(context.Background(), "tcep_SanitizerSensor_1", types.ContainerRemoveOptions{})
		check(err)
		_, err = cli.ContainerCreate(context.Background(), &container.Config{
			Image: commitResp.ID,
		}, &container.HostConfig{
			Binds:       []string{os.Getenv("HOST_LOG_PATH") + ":/app/logs"},
			NetworkMode: "node-manager-net",
			PortBindings: nat.PortMap{
				"3301/tcp": []nat.PortBinding{nat.PortBinding{"", "3301"}},
			},
			Privileged: true,
			IpcMode:    "shareable",
		}, &network.NetworkingConfig{
			map[string]*network.EndpointSettings{
				"tcep_main": &network.EndpointSettings{
					Aliases: []string{"SanitizerSensor"},
				},
			},
		}, "tcep_SanitizerSensor_1")
		check(err)
		cli.NetworkConnect(context.Background(), "tcep_main", "tcep_SanitizerSensor_1", nil)

		err = startContainers(context.Background(), cli, containerList)
		check(err)

	case "flink":

	default:
		// do nothing
	}

	log.Printf("%s successfully submitted", operatorName)
}

// (begin) added by Minh
func SaveOpFile(identifier string, language string, filePath string) {
	savePath := "operators/custom-operators/user-operator/" + language + "/" + identifier
	os.MkdirAll(savePath, os.ModePerm)

	file, err := os.Open(filePath)
	if err != nil {
		fmt.Println(err)
		return
	}
	defer file.Close()

	switch language {
	case "go":
		savePath += "/operator.go"
		break
	case "python":
		savePath += "/operator.py"
		break
	case "java":
		savePath += "/Operator.java"
		break
	case "cpp":
		savePath += "/operator.cpp"
		break
	default:
	}
	destination, err := os.Create(savePath)
	check(err)
	defer destination.Close()
	// copies operator into template folder
	nBytes, err := io.Copy(destination, file)
	if err != nil && nBytes == 0 {
		log.Println(err)
		return
	}

	log.Printf("%s successfully saved", identifier)
}

// (end) added by Mihn

// Ref: https://medium.com/@skdomino/taring-untaring-files-in-go-6b07cf56bc07
// Tar takes a source and variable writers and walks 'source' writing each file
// found to the tar writer; the purpose for accepting multiple writers is to allow
// for multiple outputs (for example a file, or md5 hash)
func Tar(src string, writers ...io.Writer) error {

	// ensure the src actually exists before trying to tar it
	if _, err := os.Stat(src); err != nil {
		return fmt.Errorf("Unable to tar files - %v", err.Error())
	}

	mw := io.MultiWriter(writers...)

	gzw := gzip.NewWriter(mw)
	defer gzw.Close()

	tw := tar.NewWriter(gzw)
	defer tw.Close()

	// walk path
	return filepath.Walk(src, func(file string, fi os.FileInfo, err error) error {

		// return on any error
		if err != nil {
			return err
		}

		// create a new dir/file header
		header, err := tar.FileInfoHeader(fi, fi.Name())
		if err != nil {
			return err
		}

		if !fi.Mode().IsRegular() {
			return nil
		}

		// update the name to correctly reflect the desired destination when untaring
		header.Name = strings.TrimPrefix(strings.Replace(file, src, "", -1), string(filepath.Separator))

		// write the header
		if err := tw.WriteHeader(header); err != nil {
			return err
		}

		// return on non-regular files (thanks to [kumo](https://medium.com/@komuw/just-like-you-did-fbdd7df829d3) for this suggested update)
		//if !info.Mode().IsRegular() {
		//	return nil
		//}

		// open files for taring
		f, err := os.Open(file)
		if err != nil {
			return err
		}

		// copy file data into tar writer
		if _, err := io.Copy(tw, f); err != nil {
			return err
		}

		// manually close here after each file operation; defering would cause each file close
		// to wait until all operations have completed.
		f.Close()

		return nil
	})
}
