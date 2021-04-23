#!/usr/bin/python

# File name: generate_geni_spec.py
# Author: Sebastian Hennig
# Date created: 18.07.2018
# Python Version: 2.7
# Description: Generates the RSpec and Docker Swarm files that are needed for execution of the simulation

import os
import sys

project_root = os.path.join(os.path.dirname(os.path.realpath(__file__)), "..")

image_user = False
image_name = False
gui_image = False

with open(os.path.join(project_root, "docker-swarm.cfg")) as cfgFile:
    for line in cfgFile:
        name, var = line.partition("=")[::2]
        if name == "registry_user":
            image_user = var.rstrip()
        elif name == "tcep_image":
            image_name = var.rstrip()
        elif name == "gui_image":
            gui_image = var.rstrip()


####
# CONSTANTS
####
ntp_container = "nserver"
viv_container = "viv"
master_hostname = "node0"
publisher_node_count = 2
tcep_image = image_user + "/" + image_name

# Read GENI wrapper template
template_file = open(os.path.join(project_root, "scripts/templates/geni_rspec.xml"), "r")
template = template_file.read()
template_file.close()

# Read GENI node template
nodes = ""
template_node_file = open(os.path.join(project_root, "scripts/templates/rspec_node.xml"), "r")
template_node = template_node_file.read()
template_node_file.close()

# Add nodes with ascending IDs
for i in range(0, int(sys.argv[1])):
    node = template_node
    node = node.replace("{{node-id}}", ("node-" + str(i)))
    nodes += node

template = template.replace("{{nodes}}", nodes)
if not os.path.exists(sys.argv[2]):
    os.mkdir(sys.argv[2])

# Write the GENI RSpec file
out_file = open(sys.argv[2] + "/rspec-" + sys.argv[1] +  ".xml", "w")
out_file.write(template)
out_file.close()

file = open(os.path.join(project_root, "scripts/templates/docker-stack.yml"), "r")
docker_stack = file.read()
file.close()

# NTP Server
file = open(os.path.join(project_root, "scripts/templates/ntpserver-docker.yml"), "r")
ntp_server = file.read()\
    .replace("{{name}}", ntp_container)\
    .replace("{{inport}}", "2200")\
    .replace("{{outport}}", "2200")\
    .replace("{{hostname}}", master_hostname)
file.close()

# Vivaldi node
file = open(os.path.join(project_root, "scripts/templates/vivaldi-docker.yml"), "r")
vivaldi_server = file.read()\
    .replace("{{name}}", viv_container)\
    .replace("{{ntpcontainer}}", ntp_container)\
    .replace("{{inport}}", "2549")\
    .replace("{{outport}}", "2549") \
    .replace("{{image}}", tcep_image) \
    .replace("{{hostname}}", master_hostname)
file.close()

# Simulator node
file = open(os.path.join(project_root, "scripts/templates/simulator-docker.yml"), "r")
simulator_server = file.read()\
    .replace("{{name}}", "simulator")\
    .replace("{{vivaldicontainer}}", viv_container)\
    .replace("{{inport}}", "2202")\
    .replace("{{outport}}", "2202") \
    .replace("{{tcepinport}}", "25001") \
    .replace("{{tcepoutport}}", "25001") \
    .replace("{{image}}", tcep_image) \
    .replace("{{hostname}}", master_hostname)
file.close()

# GUI node
file = open(os.path.join(project_root, "scripts/templates/gui-docker.yml"), "r")
gui_server = file.read() \
    .replace("{{name}}", "gui") \
    .replace("{{inport}}", "3000") \
    .replace("{{outport}}", "3000") \
    .replace("{{image}}", gui_image) \
    .replace("{{hostname}}", master_hostname)
file.close()

# Emptyapp node
file = open(os.path.join(project_root, "scripts/templates/emptyapp-docker.yml"))
emptyapp_node = file.read()
file.close()

# Publisher node template
file = open(os.path.join(project_root, "scripts/templates/publisher-docker.yml"))
publisher_node = file.read()
file.close()

publisher_nodes = ""
emptyapp_nodes = ""


node = publisher_node
node = node \
    .replace("{{name}}", ("DoorSensor")) \
    .replace("{{inport}}", str(3300)) \
    .replace("{{outport}}", str(3300)) \
    .replace("{{image}}", tcep_image) \
    .replace("{{hostname}}", "node1")
publisher_nodes += node

node = publisher_node
node = node \
    .replace("{{name}}", ("SanitizerSensor")) \
    .replace("{{inport}}", str(3301)) \
    .replace("{{outport}}", str(3301)) \
    .replace("{{image}}", tcep_image) \
    .replace("{{hostname}}", "node2")
publisher_nodes += node

# Loop the given number of times minus the times of data procuders we have
# to not exceed total host code
for i in range(publisher_node_count + 1, int(sys.argv[1])):
    node = emptyapp_node
    node = node\
        .replace("{{name}}", ("app" + str(i)))\
        .replace("{{inport}}", str(3300 + i))\
        .replace("{{outport}}", str(3300 + i)) \
        .replace("{{image}}", tcep_image) \
        .replace("{{hostname}}", ("node" + str(i)))
    emptyapp_nodes += node

# Concatenate all specifications and output to file
containers = ntp_server + vivaldi_server + gui_server + simulator_server + publisher_nodes + emptyapp_nodes
docker_stack = docker_stack.replace("{{containers}}", containers)

file = open(sys.argv[2] + "/docker-stack-" + sys.argv[1] +  ".yml", "w")
file.write(docker_stack)
file.close()

print("Generated successfully at " + sys.argv[2] + " with image name\n\n" +tcep_image + "\n")

file = open(os.path.join(project_root, "docker-stack.yml"), "w")
file.write(docker_stack)
file.close() 

print("Copied to the projects root directory to be used for the experiment")


