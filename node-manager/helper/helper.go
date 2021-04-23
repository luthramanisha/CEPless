package helper

import (
	"encoding/json"
	"errors"
	"github.com/docker/docker/pkg/jsonmessage"
	"io"
)

// https://github.com/leopoldxx/godocker/blob/master/docker.go
func DetectErrorMessage(in io.Reader) error {
	dec := json.NewDecoder(in)

	for {
		var jm jsonmessage.JSONMessage
		if err := dec.Decode(&jm); err != nil {
			if err == io.EOF {
				break
			}
			return err
		}
		//log.Printf("docker resp: %+v", jm)
		// skip progress message
		//if jm.Progress == nil {
		//glog.Infof("%v", jm)
		//}
		if jm.Error != nil {
			return jm.Error
		}

		if len(jm.ErrorMessage) > 0 {
			return errors.New(jm.ErrorMessage)
		}
	}
	return nil
}
