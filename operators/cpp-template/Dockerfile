FROM gcc:10.1

# Copy the current folder which contains C++ source code to the Docker image under /usr/src
COPY . /src

# Specify the working directory
WORKDIR /src

# Install cpp_redis
RUN apt update && apt install -y cmake && \
    git clone https://github.com/cpp-redis/cpp_redis.git && \
    cd cpp_redis && \
    git submodule init && git submodule update && \
    mkdir build && cd build && \
    cmake .. -DCMAKE_BUILD_TYPE=Release && \
    make && make install

# Compile the project
WORKDIR /src/build

RUN echo "Starting compilation"; (cmake .. && make) && echo "Finished compilation: succedeed" || (echo "Finished compilation: failed" && exit 1)

# Run the program output from the previous step
CMD ["./cpp_template"]