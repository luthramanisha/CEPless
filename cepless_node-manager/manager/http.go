package manager

import (
	"agent/deployment"
	"agent/rand"
	"agent/submit"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"log"
	"net/http"
	"os"

	"net/smtp"

	"github.com/go-redis/redis"

	"github.com/rs/cors"
)

var cacheClient = redis.NewClient(&redis.Options{
	Addr:     "localhost:6379",
	Password: "", // no password set
	DB:       0,  // use default DB
})

var addrInPefix = "cep-stream-incoming-"
var addrOutPrefix = "cep-stream-outgoing-"

func check(e error) {
	if e != nil {
		log.Println(e)
		return
	}
}

func parseJSON(r *http.Request) []byte {
	// Read body
	b, err := ioutil.ReadAll(r.Body)
	defer r.Body.Close()
	if err != nil {
		log.Fatalf("Could not parse request... %v", err)
		return nil
	}
	return b
}

func httpRequestOperator(w http.ResponseWriter, r *http.Request) {
	b := parseJSON(r)
	if b == nil {
		log.Fatalf("Could not parse request... Not deploying operator")
		return
	}

	// Unmarshal
	var operatorReq OperatorReq
	err := json.Unmarshal(b, &operatorReq)
	if err != nil {
		log.Fatalf("Could not parse request... Not deploying operator %v", err)
		return
	}

	nodeResponse := registerNode(operatorReq)

	deployment.DeployNewOperator(operatorReq.Name, operatorReq.RequestIdentifier, nodeResponse.AddrIn, nodeResponse.AddrOut)

	response := make(map[string]string)
	response["addrIn"] = nodeResponse.AddrIn
	response["addrOut"] = nodeResponse.AddrOut
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	errEncode := json.NewEncoder(w).Encode(response)
	if errEncode != nil {
		log.Fatalf("Could not send response... %v", errEncode)
		return
	}
}

func httpUpdateOperator(w http.ResponseWriter, r *http.Request) {
	b := parseJSON(r)
	if b == nil {
		log.Fatalf("Could not parse request... Not deploying operator")
		return
	}

	// Unmarshal
	var operatorReq OperatorUpdateReq
	err := json.Unmarshal(b, &operatorReq)
	if err != nil {
		log.Fatalf("Could not parse request... Not deploying operator %v", err)
		return
	}

	deployment.UpdateOperator(operatorReq.RequestIdentifier, operatorReq.Name, operatorReq.NewRequestIdentifier, operatorReq.OperatorAddrIn, operatorReq.OperatorAddrOut)

	response := make(map[string]string)
	response["addrIn"] = operatorReq.OperatorAddrIn
	response["addrOut"] = operatorReq.OperatorAddrOut
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	errEncode := json.NewEncoder(w).Encode(response)
	if errEncode != nil {
		log.Fatalf("Could not send response... %v", errEncode)
		return
	}
}

// (begin) added by Matheus
func httpBuildOperator(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case "POST":

		// parse JSON
		decoder := json.NewDecoder(r.Body)
		var op OperatorSubmit
		err := decoder.Decode(&op)
		check(err)

		log.Printf("Received build request for %s\n", op.OperatorName)

		// setup file path
		filePath := fmt.Sprintf("/tmp/%s/", op.OperatorName)
		err = os.MkdirAll(filePath, os.ModePerm)
		check(err)
		switch op.Language {
		case "go":
			filePath += "operator.go"
		case "python":
			filePath += "operator.py"
		case "java":
			filePath += "Operator.java"
		case "cpp":
			filePath += "operator.cpp"
		default:
			w.WriteHeader(http.StatusBadRequest)
			w.Write([]byte(`{"message": "Unsupported language"}`))
			return
		}
		// write source code to file
		file, err := os.Create(filePath)
		check(err)
		defer file.Close()
		_, err = file.WriteString(op.SourceCode)
		check(err)

		// build operator image
		buildLogs, err := submit.BuildOperator(op.OperatorName, filePath, op.Language)

		// if build failed
		if err != nil {
			// if compilation error
			if err, ok := err.(*submit.CompilationError); ok {

				resp := map[string]string{
					"log":     string(err.Log),
					"message": "Compilation error",
				}
				response, err := json.Marshal(resp)
				check(err)
				w.WriteHeader(http.StatusBadRequest)
				w.Write(response)
				return
			}
			// if another unknown error
			log.Println(err)
			resp := map[string]string{
				"error":   fmt.Sprintf("%v", err),
				"message": "Internal server error",
			}
			response, err := json.Marshal(resp)
			check(err)
			w.WriteHeader(http.StatusInternalServerError)
			w.Write(response)
			return
		}

		resp := map[string]string{
			"message": "Operator successfully built",
			"log":     string(buildLogs),
		}
		response, err := json.Marshal(resp)
		w.WriteHeader(http.StatusCreated)
		w.Write(response)

	case "OPTIONS":
		w.WriteHeader(http.StatusOK)
		w.Header().Set("Allow", "POST, OPTIONS")
	default:
		log.Printf("%s: Unsupported method for /build", r.Method)
		w.WriteHeader(http.StatusMethodNotAllowed)
		w.Write([]byte(`{"message": "Method not supported"}`))
	}
}

func httpSubmitOperator(w http.ResponseWriter, r *http.Request) {

	switch r.Method {
	case "POST":
		// parse JSON
		decoder := json.NewDecoder(r.Body)
		var op OperatorSubmit
		err := decoder.Decode(&op)
		check(err)

		// build file path
		filePath := fmt.Sprintf("/tmp/%s/", op.OperatorName)
		switch op.Language {
		case "go":
			filePath += "operator.go"
		case "python":
			filePath += "operator.py"
		case "java":
			filePath += "Operator.java"
		case "cpp":
			filePath += "operator.cpp"
		default:
			w.WriteHeader(http.StatusBadRequest)
			w.Write([]byte(`{"message": "Unsupported language"}`))
			return
		}

		submit.SubmitOperator(op.OperatorName)
		submit.SaveOpFile(rand.String(10), op.Language, filePath)

		response := map[string]string{
			"message": "Operator successfully submitted",
		}

		resp, err := json.Marshal(response)
		w.WriteHeader(http.StatusCreated)
		w.Write([]byte(resp))
	case "OPTIONS":
		w.WriteHeader(http.StatusOK)
		w.Header().Set("Allow", "POST, OPTIONS")

	default:
		log.Printf("%s: Unsupported method", r.Method)
		w.WriteHeader(http.StatusMethodNotAllowed)
		w.Write([]byte(`{"message": "Method not supported"}`))
	}
}
// (end) added by Matheus

// (begin) added by Minh
func httpSubmitFeedback(w http.ResponseWriter, r *http.Request) {
	log.Printf("%s method", r.Method)
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "POST, GET, OPTIONS, PUT, DELETE")
	w.Header().Set("Access-Control-Allow-Headers", "Accept, Content-Type, Content-Length, Accept-Encoding, X-CSRF-Token, Authorization")
	w.Header().Set("Content-Type", "application/json")
	switch r.Method {
	case "POST":
		decoder := json.NewDecoder(r.Body)
		var fb FeedbackSubmit
		err := decoder.Decode(&fb)
		check(err)

		auth := smtp.PlainAuth("", "komlab.serverless2020@gmail.com", "KOMlab2020", "smtp.gmail.com")

		to := []string{"manisha.luthra@kom.tu-darmstadt.de", "minhtung.tran@stud.tu-darmstadt.de", "thekhang.nguyen@stud.tu-darmstadt.de", "matheus.sousav@gmail.com"}
		subject := "Subject: " + "Feedback from CEPless web client" + "!\n"
		msg := []byte("To: manisha.luthra@kom.tu-darmstadt.de\r\n" + subject + "\n" + fb.Qa + "\n" + fb.Remarks + "\n")
		log.Println("sending email")
		err = smtp.SendMail("smtp.gmail.com:587", auth, "komlab.serverless2020@gmail.com", to, msg)
		if err != nil {
			log.Fatal(err)
		}
	case "OPTIONS":
		w.WriteHeader(http.StatusOK)
		w.Header().Set("Allow", "POST, OPTIONS")

	default:
		log.Printf("%s: Unsupported method", r.Method)
		w.WriteHeader(http.StatusMethodNotAllowed)
		w.Write([]byte(`{"message": "Method not supported"}`))
	}
}

// (end) added by Minh

// altered by Matheus
func Open() {

	mux := http.NewServeMux()

	mux.HandleFunc("/requestOperator", httpRequestOperator)
	mux.HandleFunc("/updateOperator", httpUpdateOperator)
	mux.HandleFunc("/submitOperator", httpSubmitOperator)
	mux.HandleFunc("/buildOperator", httpBuildOperator)
	mux.HandleFunc("/submitFeedback", httpSubmitFeedback)

	deployment.Init()

	handler := cors.Default().Handler(mux)

	if err := http.ListenAndServe(":25003", handler); err != nil {
		panic(err)
	}
}
