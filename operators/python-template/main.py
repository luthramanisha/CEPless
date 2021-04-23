import os
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from redis.client import Redis

import op

addrIn = os.getenv("ADDR_IN")
addrOut = os.getenv("ADDR_OUT")
listen = False

client = Redis(host='redis', port=6379)

def start(write, read):
    global listen
    listen = True
    listenChannel(addrIn)

def send(item):
    print("Sending item " + str(item) + "into queue " + addrOut)
    try:
        client.rpush(addrOut, item)
    except Exception as e:
        print("Failed to put item into queue " + str(e))

def listenChannel(channelName):
    print("Using channel " + channelName)
    keys = [channelName]
    while listen:
        try:
            task = client.blpop(keys=keys, timeout=1)
        except Exception as e:
            print("Failed to get task from queue: " + str(e))
            break
        print('Got Task: ' + str(task))
        if task == None or len(task) < 2:
            print('No event in queue...')
            continue
        processItem(task[1])

def processItem(task):
    result = op.process(task)
    send(result)

def stop(read, write):
    global listen
    print("Stop processing items")
    listen = False

def main():
    if len(addrIn) == 0:
        print("Input address not defined but is required... Stopping...")

    if len(addrOut) == 0:
        print("Output address not defined but is required... Stopping...")

    start(None, None)
    print("AddrIn is " + addrIn)

    handler = RequestHandler()
    try:
        with ThreadingHTTPServer(('',25003), handler) as httpd:
            httpd.serve_forever()
    except Exception as e:
        print(e)

class RequestHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/start":
            start(None,None)
            self.send_response(200)
            self.end_headers()
        elif self.path == "/stop":
            stop(None,None)
            self.send_response(200)
            self.end_headers()
        else:
            self.send_response(404)
            self.end_headers()

if __name__ == "__main__":
    main()
