package manager

import (
	"log"
)

type OperatorResponse struct {
	AddrIn  string
	AddrOut string
}

type OperatorReq struct {
	Name              string `json:"name"`
	RequestIdentifier string `json:"requestIdentifier"`
}

type OperatorUpdateReq struct {
	Name                 string `json:"name"`
	RequestIdentifier    string `json:"requestIdentifier"`
	NewRequestIdentifier string `json:"newRequestIdentifier"`
	OperatorAddrIn       string `json:"addrIn"`
	OperatorAddrOut      string `json:"addrOut"`
}

// added by Matheus
type OperatorSubmit struct {
	OperatorName string
	Language     string
	SourceCode   string
}

// added by Minh
type FeedbackSubmit struct {
	Qa		string
	Remarks	string
}

var nodes = make(map[string]OperatorResponse)

func registerNode(operator OperatorReq) OperatorResponse {
	name := operator.Name
	log.Printf("Deploying operator with name %v", name)
	var operatorResponse = OperatorResponse{
		addrInPefix + operator.RequestIdentifier, addrOutPrefix + operator.RequestIdentifier,
	}
	nodes[name] = operatorResponse
	return operatorResponse
}
