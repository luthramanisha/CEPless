#include <iostream>
#include <cpp_redis/cpp_redis>
#include <vector>
#include <string>
#include "operator.h"

class Handler {
	int listen = 0;
	cpp_redis::client client;
	std::string addrIn;
	std::string addrOut;

	public: 
		Handler() {
			client.connect("redis", 6379,
				[](const std::string &host, std::size_t port, cpp_redis::connect_state status) {
				if (status == cpp_redis::connect_state::dropped) {
					std::cout << "client disconnected from " << host << ":" << port << std::endl;
				}
			});
			addrIn = getenv("ADDR_IN");
			addrOut = getenv("ADDR_OUT");
		}
		

	void listenChannel(std::string addr) {
		while (this->listen == 1) {
			std::vector<std::string> keys;
			std::cout << "11111" << std::endl;
			keys.push_back(addr);
			
			client.blpop(keys, 1, [](cpp_redis::reply& reply) {
				if (reply.is_error()) {
					std::cout << "Blpop error: " << reply.as_string() << std::endl;
				}
				if (reply.is_string()) {
					std::cout << "Blpop response: " << reply.as_string() << std::endl;
				} else {
					std::cout << "No event in queue..." << std::endl;
				}
			});
		}
	}
	void processItem(std::vector<std::string> task) {
		std::vector<std::string> processed = process(task);
		send(processed);
	}

	void send(std::vector<std::string> item) {
		client.rpush(addrOut, item);
	}

	void start() {
		std::cout << "Listening on channel %s" << this->addrIn << std::endl;
		this->listen = 1;
		listenChannel(addrIn);
	}

};

int main()
{
    Handler handler;
    handler.start();

}

// Run program: Ctrl + F5 or Debug > Start Without Debugging menu
// Debug program: F5 or Debug > Start Debugging menu

// Tips for Getting Started: 
//   1. Use the Solution Explorer window to add/manage files
//   2. Use the Team Explorer window to connect to source control
//   3. Use the Output window to see build output and other messages
//   4. Use the Error List window to view errors
//   5. Go to Project > Add New Item to create new code files, or Project > Add Existing Item to add existing code files to the project
//   6. In the future, to open this project again, go to File > Open > Project and select the .sln file
