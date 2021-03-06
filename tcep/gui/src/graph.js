const SERVER = "127.0.0.1"
const GUI_PORT = 3000

// Holds the current data received from the GUI server
// Used to determine if the data changed so a redraw is not unnecessarily done
var currentData = null

var width = 1400;
var height = 1000;
const SERVER_URL = `http://${SERVER}:${GUI_PORT}/data`;
var fontSize = 12;
var fontColor = "#000";
var greenColor = '#00cc00';


function setNodePositions(nodes, consumerData) {
    const y = 250
    const xOff2 = 300
    const yOff2 = 100
    let numPublishers = 0

    if (currentViewMode() == 0 || consumerData == null || Object.keys(consumerData).length == 0) {
        nodes.forEach(n => {
            //n.fixed = true // TODO change this if you want to have fixed nodes
            if (n.name.indexOf('Consumer') !== -1) {
                n.x = 1200
                n.y = y + 100
                n.fixed = true
            } else if ((n.operators && n.operators.length > 0)|| n.usage === 2) {
                let found = n.operators.find(o => {
                    return o.name.indexOf('P:') !== -1
                })
                
                if (found) {
                    n.x = 250
                    n.y = y + (150 * (numPublishers++)) // Every publisher with 50 offset 
                    n.fixed = true
                }
            } else {
                // Node does not host operator => Place it a little bit outside of graph
                var xPos = localStorage.getItem(`${n.name}X`);
                var yPos = localStorage.getItem(`${n.name}Y`);
                if (xPos === 'undefined' || yPos === 'undefined' || !xPos || !yPos) {
                    xPos = xOff2 + ((width - 100 - xOff2) * Math.random());
                    yPos = yOff2 + ((height - 100 - yOff2) * Math.random());
                    console.log('setting', xPos)
                    console.log('text')
                    localStorage.setItem(`${n.name}X`, xPos);
                    localStorage.setItem(`${n.name}Y`, yPos);
                }
                console.log(xPos, yPos)
                n.x = xPos
                n.y = yPos
                n.fixed = true
            }
        })
        return nodes;
    } 

    const coordinates = consumerData.coordinates;
    let coordXMin = null;
    let coordXMax = null;
    let coordYMin = null;
    let coordYMax = null;
    Object.keys(coordinates).forEach(host => {
        const coord = coordinates[host];
        if (coord.x < coordXMin || coordXMin == null) {
            coordXMin = coord.x;
        }
        if (coord.y < coordYMin || coordYMin == null) {
            coordYMin = coord.y;
        }
        if (coord.x > coordXMax || coordXMax == null) {
            coordXMax = coord.x;
        }
        if (coord.y > coordYMax || coordYMax == null) {
            coordYMax = coord.y;
        }
    })

    var yOffset = 0;
    if (coordYMin < 0) {
        yOffset = (-1) * coordYMin
        coordYMin += yOffset
        coordYMax += yOffset
    }
    var xOffset = 0;
    if (coordXMin < 0) {
        xOffset = (-1) * coordXMin;
        coordXMin += xOffset
        coordXMax += xOffset
    }

    nodes.forEach(n => {
        var name = n.name;
        if (n.name.indexOf('Consumer') !== -1) {
            name = 'node0';
        }
        let nodeCoordinates = coordinates[name];
        if (name == "node1") {
            nodeCoordinates = coordinates["DoorSensor"];
        } else if (name == "node2") {
            nodeCoordinates = coordinates["SanitizerSensor"];
        }

        var xPosrand = localStorage.getItem(`${n.name}Xrand`);
        var yPosrand = localStorage.getItem(`${n.name}Yrand`);

        if (nodeCoordinates) {
            n.x = ((nodeCoordinates.x + xOffset) - coordXMin) / (coordXMax - coordXMin) * (width/2) + 300 //  (nodeCoordinates.x * 30) // (nodeCoordinates.x * 1000) / width;
            n.y = ((nodeCoordinates.y + yOffset) - coordYMin) / (coordYMax - coordYMin) * (height/2) + 150 //(nodeCoordinates.y * 30) // * 1000) / height;
        } else if (xPosrand && yPosrand) {
            n.x = xPosrand;
            n.y = yPosrand;
        } else {
            var randomNodeCoordinates = coordinates[Object.keys(coordinates)[Math.floor(Math.random() * (Object.keys(coordinates).length -1))]];
            n.x = ((randomNodeCoordinates.x + xOffset) - coordXMin) / (coordXMax - coordXMin) * (width/2) + 300 + Math.random() * 50
            n.y = ((randomNodeCoordinates.y + xOffset) - coordXMin) / (coordXMax - coordXMin) * (width/2) + 300 + Math.random() * 50
            localStorage.setItem(`${n.name}Xrand`, n.x);
            localStorage.setItem(`${n.name}Yrand`, n.y);
        }
        n.fixed = true;
    });

    return nodes
}

function getMetadataForLink(links, d, previousNodes) {
    var metadata = null;

    let sourceNode = d.source;
    let targetNode = d.target

    if (d.previousLink) {
        // replace original nodes with previous ones to get the data that was collected before the transition
        sourceNode = previousNodes.find(n => n.name === d.source.name)
        targetNode = previousNodes.find(n => n.name === d.target.name)
    }


    sourceNode.operators.find(op => {
        var parent = op.parents.find(p => {
            // Check wether this parent is hosted at target to get the right metadata
            var targetOperator = targetNode.operators.find(target => {
                return target.name === p.operatorName;
            })
            return !!targetOperator
        })
        if (parent) {
            metadata = parent
            return true
        }
    })
    if (!metadata) {
        return null;
    }

    // now check if there is another link from the same node but with newer metadata
    let duplicateLinks = links.filter(l => {
        return l.source === targetNode && l.target === sourceNode;
    })

    if (duplicateLinks && !d.previousLink) {
        duplicateLinks.forEach(l => {
            var newMetadata = null;
            l.source.operators.find(op => {
                var parent = op.parents.find(p => {
                    // Check wether this parent is hosted at target to get the right metadata
                    var targetOperator = l.target.operators.find(target => {
                        return target.name === p.operatorName;
                    })
                    return !!targetOperator
                })
                if (parent) {
                    newMetadata = parent
                    return true
                }
            })
            
            if (metadata && newMetadata && metadata.timestamp < newMetadata.timestamp) {
                metadata = null; // Metadata will be filled by the newer metadata in other iterations
            }
        })
    }

    return metadata
}

var addNodeTextSVG = (node, textFn) => {
    return node.append("svg:text").text(textFn).style("fill", fontColor).style("font-family", "Arial").style("font-size", fontSize).attr("x", 0)
}

var operatorTextFn = (index) => {
    return function(d, i) {
        var text = ""
        if (d.operators && d.operators.length > index) {
            if (d.operators[index].name.indexOf('-') === -1) {
                return d.operators[index].name
            }
            text = d.operators[index].name.replace('Node', '').substr(0, d.operators[index].name.indexOf('-') - 4) +  " (" + d.operators[index].algorithm + ")"
        }
        return text
    }
}

var legendX = 30;
var legendY = 0;
var legendY2 = 10;
var circleRadius = 5;
var previousTransitionColor = "#3232ff88"
var nodes = null;
var svg = d3.select("body").append("svg")
    .attr("width", width)
    .attr("height", height)
    .attr('x', 100)
    .attr('y', 0)

svg.append("svg:image")
    .attr('x', legendX - 20)
    .attr('y', legendY)
    .attr('width', 30)
    .attr('height', 40)
    .attr("xlink:href", "resources/GENI.png")

svg.append("circle").attr("cx", legendX).attr("cy", legendY2 + 60).attr("r", circleRadius)
svg.append("svg:text").text("Nodes without operators").attr("x", legendX + 10).attr("y", legendY2 + 65).style("font-family", "Arial").style("font-size", fontSize).style('fill', fontColor)
svg.append('line').attr("x1", legendX - 30).attr("x2", legendX).attr("y1", legendY2 + 90).attr("y2", legendY2 + 90).attr("stroke-width", 2).attr("stroke", previousTransitionColor).style("stroke-dasharray", ("3, 3")) 
svg.append("circle").attr("cx", legendX).attr("cy", legendY2 + 90).attr("r", circleRadius).attr("fill", previousTransitionColor)
svg.append("svg:text").text("Nodes/Links with operators before transition").attr("x", legendX + 10).attr("y", legendY2 + 95).style("font-family", "Arial").style("font-size", fontSize).style('fill', fontColor)

svg.append('line').attr("x1", legendX - 30).attr("x2", legendX).attr("y1", legendY2 + 120).attr("y2", legendY2 + 120).attr("stroke-width", 2).attr("stroke", greenColor).style("stroke-dasharray", ("3, 3"))
svg.append("circle").attr("cx", legendX).attr("cy", legendY2 + 120).attr("r", circleRadius).attr("fill", greenColor)
svg.append("svg:text").text("Nodes/Links currently in use").attr("x", legendX + 10).attr("y", legendY2 + 125).style("font-family", "Arial").style("font-size", fontSize).style('fill', fontColor)
svg.append("svg:text").attr('class','clusterNodes').attr("x", legendX + 20).attr("y", legendY + 35).style("font-family", "Arial").style("font-size", fontSize).style('fill', fontColor)
svg.append("svg:text").attr('class', 'transitionTime').attr("x", legendX + 10).attr("y", legendY2 + 155).style("font-family", "Arial").style("font-size", fontSize).style('fill', fontColor)
svg.append("svg:text").attr('class', 'strategyName').attr("x", legendX + 10).attr("y", legendY2 + 185).style("font-family", "Arial").style("font-size", fontSize).style('fill', fontColor)
    
var force;

function createGraph(json, links) {
    force = d3.layout.force()
    .gravity(0)
    .distance(300)
    .charge(0)
    .linkDistance(200)
    .size([width, height]);

    
    nodes = json.nodes;

    svg.selectAll(".clusterNodes").text("cluster nodes: " + json.nodes.length)
    svg.selectAll(".transitionTime").text("Transition time: " + (json.transitionTime ? `${json.transitionTime} sec` : "not set"))

    if (json.status && json.status.placementStrategy) {
        svg.selectAll(".strategyName").text("Placement strategy: " + json.status.placementStrategy)
    }
    
    force
        .nodes(json.nodes)
        .links(links)
        .start();

    var link = svg.selectAll(".link")
    .data(links, (l) => (l.source.name + l.target.name)).enter().append("g").attr('class', 'link')
    .append("line")
    .attr("class", function(d) { 
        if (d.hardLink || d.dottedLink || d.previousLink) {
            return "animatedLink";
        }
    })
    .style("stroke-width", function(d) {
        if (d.hardLink) {
            return "8px"
        } else {
            return "3px"
        }
    })
    .style("stroke-dasharray", (d) => {
        if (d.dottedLink) {
            return ("3, 3")
        } 
        return undefined
    }) 
    .style('stroke', (d) => {
        if (d.hardLink || (d.dottedLink && !d.previousLink)) {
            return greenColor
        } else if (d.previousLink) {
            return previousTransitionColor
        } else {
            return "#BBB"
        }
    })

    var linkText = svg.selectAll(".link")
    .append("text")
    .attr('class', 'linkText')
    .data(force.links())
    .text(function(d) {
        if (d.hardLink || d.dottedLink) {
            return ""
        } else if (d.target && d.target.operators && d.target.operators[0]) {
            let metadata = d.target.operators[0].parents[0]
            if (d && d.transitionTimeLabel) {
                return `T [Mig ${d.transitionTimeLabel}]`
            }
        }
        return "T"
    })
    .attr("x", function(d) { return (d.source.x + (d.target.x - d.source.x) * 0.5); })
    .attr("y", function(d) { return (d.source.y + (d.target.y - d.source.y) * 0.5); })
    .attr("dy", ".25em")
    .attr('class', 'link-data')
    .style("font-family", "Arial").style("font-size", fontSize).style('fill', '#000')

    var bdpText = svg.selectAll(".link")
    .append("text")
    .data(force.links())
    .text(function(d) {
        if (!d.hardLink && d.source && d.source.operators) {
            let metadata = getMetadataForLink(links, d, json.previousNodes);
            if (metadata && metadata.bandwidthDelayProduct !== undefined && metadata.bandwidthDelayProduct !== null) {
                return `BDP ${metadata.bandwidthDelayProduct.toFixed(2)} Mbit`
            }
        }
        return ""
    })
    .attr("x", function(d) { return (d.source.x + (d.target.x - d.source.x) * 0.5); })
    .attr("y", function(d) { return (d.source.y + (d.target.y - d.source.y) * 0.5); })
    .attr("dy", "1.5em")
    .attr('class', 'link-data')
    .style("font-family", "Arial").style("font-size", fontSize).style('fill', '#000')

    var messageOverheadText = svg.selectAll(".link")
    .append("text")
    .data(force.links())
    .text(function(d) {
        if (!d.hardLink && d.source && d.source.operators) {
            let metadata = getMetadataForLink(links, d, json.previousNodes);
            if (metadata && metadata.messageOverhead !== undefined && metadata.messageOverhead !== null) {
                return `MO ${metadata.messageOverhead} Bytes`
            }
        }
        return ""
    })
    .attr("x", function(d) { return (d.source.x + (d.target.x - d.source.x) * 0.5); })
    .attr("y", function(d) { return (d.source.y + (d.target.y - d.source.y) * 0.5); })
    .attr("dy", "2.5em")
    .attr('class', 'link-data')
    .style("font-family", "Arial").style("font-size", fontSize).style('fill', '#000')

    var testText = svg.selectAll(".link")
    .append("text")
    .data(force.links())
    .text(function(d) {
        if (!d.hardLink && d.source && d.source.operators) {
            let latency = getLatencyForLink(json.consumerData, d.source, d.target);
            let metadata = getMetadataForLink(links, d, json.previousNodes);
            if (latency) {
                return `Latency ${latency} ms`
            }
        }
        return ""
    })
    .attr("x", function(d) { return (d.source.x + (d.target.x - d.source.x) * 0.5); })
    .attr("y", function(d) { return (d.source.y + (d.target.y - d.source.y) * 0.5); })
    .attr("dy", "3.5em")
    .attr('class', 'link-data')
    .style("font-family", "Arial").style("font-size", fontSize).style('fill', '#000')

    var div = d3.select("body").append("div")	
    .attr("class", "tooltip")				
    .style("opacity", 0);

    var node = svg.selectAll(".node")
        .data(json.nodes, (n) => n.name)
    var newNodes = node.enter().append("g")

    node
        .attr("class", "node")
        .call(force.drag)
        .on("mouseover", function(d) {	
            let textBlocks = [d.name]
            for (let key in d.operators) {
                if (d.operators[key].name.indexOf('-') === -1) {
                    textBlocks.push(d.operators[key].name)
                    continue
                }
                textBlocks.push(d.operators[key].name.replace('Node', '').substr(0, d.operators[key].name.indexOf('-') - 4) +  " (" + d.operators[key].algorithm + ")")
            }
            div.transition()		
                .duration(50)		
                .style("opacity", .9);		
            div	.html(textBlocks.join("<br/>"))	
                .style("left", (d3.event.pageX + 30) + "px")		
                .style("top", (d3.event.pageY - 28) + "px");	
            })					
        .on("mouseout", function(d) {		
            div.transition()		
                .duration(500)		
                .style("opacity", 0);	
        });

    newNodes.append("svg:circle").attr("r", 5)
        .attr("fill", (d) => {
            switch (d.usage) {
                case 1: return greenColor
                case 2: return previousTransitionColor
            }
            return "#000"
        })
        .attr("x", -8)
        .attr("y", -8)
        .attr("width", 16)
        .attr("height", 16);
        

    addNodeTextSVG(newNodes, function(d) { return d.name }).attr("dy", "2em")
    addNodeTextSVG(newNodes, function(d) { if (d.operators && d.operators.length > 0 && d.operators[0].name.indexOf('Producer') !== -1) return d.operators[0].name}).attr("dy", "3em")
    // SHOWS OPERATORS ON NODES
    /*addNodeTextSVG(node, operatorTextFn(0)).attr("dy", "3em")
    addNodeTextSVG(node, operatorTextFn(1)).attr("dy", "4em")
    addNodeTextSVG(node, operatorTextFn(2)).attr("dy", "5em")
    addNodeTextSVG(node, operatorTextFn(3)).attr("dy", "6em")*/

    force.on("tick", function() {
        link.attr("x1", function(d) { return d.source.x; })
            .attr("y1", function(d) { return d.source.y; })
            .attr("x2", function(d) { return d.target.x; })
            .attr("y2", function(d) { return d.target.y; });

        node.attr("transform", function(d) { return "translate(" + d.x + "," + d.y + ")"; });

        linkText
            .attr("x", function(d) { return (d.source.x + (d.target.x - d.source.x) * 0.5); })
            .attr("y", function(d) { return (d.source.y + (d.target.y - d.source.y) * 0.5); });
        bdpText
            .attr("x", function(d) { return (d.source.x + (d.target.x - d.source.x) * 0.5); })
            .attr("y", function(d) { return (d.source.y + (d.target.y - d.source.y) * 0.5); });
        messageOverheadText
            .attr("x", function(d) { return (d.source.x + (d.target.x - d.source.x) * 0.5); })
            .attr("y", function(d) { return (d.source.y + (d.target.y - d.source.y) * 0.5); });
        testText
            .attr("x", function(d) { return (d.source.x + (d.target.x - d.source.x) * 0.5); })
            .attr("y", function(d) { return (d.source.y + (d.target.y - d.source.y) * 0.5); });
    });
}

function getLatencyForLink(consumerData, source, destination) {
    if (consumerData == null || consumerData.latencyValues == null) {
        return null;
    }
    let value = null;
    consumerData.latencyValues.forEach(val => {
        if (val.source == "DoorSensor") {
            val.source = "node1"
        } else if (val.destination == "DoorSensor") {
            val.destination = "node1"
        } else if (val.source == "SanitizerSensor") {
            val.source = "node2"
        } else if (val.destination == "SanitizerSensor") {
            val.destination = "node2"
        }
        console.log(val.source, source.name, val.destination, destination.name)
        if (val.source == source.name && val.destination == destination.name) {
            value = val.distance.toFixed(2);
        }
    });
    return value;
}

function isJSONEqual(a, b) {
    return JSON.stringify(a) === JSON.stringify(b)
}

function loadData(cb) {
    d3.json(SERVER_URL, function(error, json) {
        if (error) throw error;
        currentData = json
        console.log(json)
        var links = []
    
        // Find FilterNode
        var filterNodeIndices = []
        json.nodes.forEach((n, i) => {
            if (!n.operators) {
                return
            }
            n.operators.forEach(o => {
                if (o.name.indexOf('FilterNode') !== -1) {
                    filterNodeIndices.push(i)
                }
            })
        })
        var consumerNode = json.nodes.find(n => {
            return n.name === "node0 (Consumer)";
        })
    
        // CONSUMER LINK
        filterNodeIndices.forEach(i => {
            if (consumerNode && json.nodes[i]) {
                links.push({
                    source: consumerNode,
                    target: json.nodes[i],
                    hardLink: true,
                    weight: 0.1,
                })
            }
        })
        
        // Find StreamNode to connect to producers //Update based on number of stream nodes currently 2
        var streamNodeIndex = [-1, -1]
        var count = 0
        json.nodes.forEach((n, i) => {
            if (!n.operators) {
                return
            }
            n.operators.forEach(o => {
                if (o.name.indexOf('StreamNode') !== -1) {
                    streamNodeIndex[count] = i
                    count++
                }
            })
        })
    
        json.nodes.forEach((n, i) => {
            if (!n.operators) {
                return;
            }
            n.operators.forEach(o => {
                if (!o.parents) {
                    return;
                }
                o.parents.forEach(parent => {
                    // find parent in nodes operators to create link
                    var parentNode = json.nodes.find(findNode => {
                        if (!findNode.operators) {
                            return;
                        }
                        return findNode.operators.find(findOp => {
                            return findOp.name === parent.operatorName
                        })
                    })
                    if (!parentNode) {
                        return;
                    }
                    console.log(`Node ${n.name} is connected to ${parentNode.name} because ${o.name} has parent ${parent.operatorName}`)
    
                    // Do not add link if node is the same
                    if (n.name === parentNode.name) {
                        return
                    }
    
                    // Do not add link if link already exists
                    var existingLink = links.find(l => {
                        if (l.source.name === n.name && l.target.name === parentNode.name && l.dottedLink) {
                            return true;
                        }
                    })
    
                    if (existingLink) {
                        return;
                    }
    
                    links.push({
                        source: n,
                        target: parentNode,
                        dottedLink: true,
                        weight: 0.1,
                    })
                });
            });
        });
    
        // add previous nodes links
        json.previousNodes.forEach((n, i) => {
            if (!n.operators) {
                return;
            }
            n.operators.forEach(o => {
                if (!o.parents) {
                    return;
                }
                o.parents.forEach(parent => {
                    // find parent in nodes operators to create link
                    var parentNode = json.previousNodes.find(findNode => {
                        if (!findNode.operators) {
                            return;
                        }
                        return findNode.operators.find(findOp => {
                            return findOp.name === parent.operatorName
                        })
                    })
                    if (!parentNode) {
                        return;
                    }
                    console.log(`Node ${n.name} is connected to ${parentNode.name} because ${o.name} has parent ${parent.operatorName}`)
    
                    // Do not add link if node is the same
                    if (n.name === parentNode.name) {
                        return
                    }
    
                    // Do not add link if link already exists
                    var existingLink = links.find(l => {
                        if (l.source.name === n.name && l.target.name === parentNode.name && l.dottedLink) {
                            return true;
                        } else if (l.target.name === n.name && l.source.name === parentNode.name && l.dottedLink) {
                            return true;
                        }
                    })
    
                    if (existingLink) {
                        return;
                    }
    
    
                    // find node in original nodes array, otherwise d3 has a problem with finding the suitable one
                    let originalN = json.nodes.find(on => on.name === n.name);
                    let originalParent = json.nodes.find(pn => pn.name === parentNode.name);

                    links.push({
                        source: originalN,
                        target: originalParent,
                        dottedLink: true,
                        previousLink: true,
                        weight: 0.1,
                    })
                });
            });
        });
    
        json.transitions.forEach((t) => {
    
            // get index of node
            let sourceIndex = -1
            let targetIndex = -1
            json.nodes.forEach((node, i) => {
                if (node.name === t.source) {
                    sourceIndex = i
                }
            })
    
            json.nodes.forEach((node, i) => {
                if (node.name === t.target) {
                    targetIndex = i
                }
            })
    
            if (sourceIndex === -1 || targetIndex === -1) {
                // No nodes for transition found
                return;
            }
    
            if (sourceIndex === targetIndex) {
                return;
            }

            let opTransName = t.opName
            if (opTransName.indexOf('StreamNode') !== -1) {
                opTransName = opTransName.replace('StreamNode', '')
                opTransName = 'Stream' + opTransName.substr(0, 2);
            } else if (opTransName.indexOf('FilterNode') !== -1) {
                opTransName = opTransName.replace('FilterNode', '')
                opTransName = 'Filter' + opTransName.substr(0, 2);
            } else if (opTransName.indexOf('JoinNode') !== -1) {
                opTransName = opTransName.replace('JoinNode', '')
                opTransName = 'Join' + opTransName.substr(0, 2);
            }

            let found = false
            links.forEach((ext) => {
                if (ext.source === sourceIndex && ext.target === targetIndex && ext.transitionTimeLabel !== undefined) {
                    found = true
                    ext.transitionTimeLabel = `${ext.transitionTimeLabel}, ${opTransName} ${t.migrationTime} ms`
                    return false
                }
            })

            // Don't add transition link that is already displayed to prevent overlaps
            if (found) {
                return
            }
    
            links.push({
                source: sourceIndex,
                target: targetIndex,
                transitionTimeLabel: `${opTransName} ${t.migrationTime} ms`,
                weight: 0.1,
                hardLink: false
            })
        })
    
        json.nodes = setNodePositions(json.nodes, json.consumerData);
    
        // Replace names of producers
        json.nodes.forEach(n => {
            if (!n.operators) {
                return;
            }
            n.operators.forEach(o => {
                if (o.name.indexOf('P:SanitizerSensor') !== -1) {
                    o.name = 'P:Producer1';
                } else if (o.name.indexOf('P:DoorSensor') !== -1) {
                    o.name = 'P:Producer2';
                }
                if (!o.parents) {
                    return
                }
                o.parents.forEach(p => {
                    if (p.operatorName.indexOf('P:SanitizerSensor') !== -1) {
                        p.operatorName = 'P:Producer1';
                    } else if (p.operatorName.indexOf('P:DoorSensor') !== -1) {
                        p.operatorName = 'P:Producer2';
                    }
                })
            })
        })
    
        cb(json, links);
    });
}

function loadAndDraw() {
    loadData(createGraph);

}

function environmentalChange(i) {
    fetch(`http://${SERVER}:${GUI_PORT}/setMembers`, {
        method: "POST",
        body: JSON.stringify({
            members: [{
                "host": "node11",
            }, {
                "host": "node12",
            }, {
                "host": "node13",
            }, {
                "host": "node14",
            }, {
                "host": "node15",
            }, {
                "host": "node16",
            }, {
                "host": "node17",
            }, {
                "host": "node18",
            }, {
                "host": "node19",
            }
        ]
        }),
        headers: {
        'Accept': 'application/json',
        'Content-Type': 'application/json'
        },
    }).then(() => {
        return fetch(`http://${SERVER}:${GUI_PORT}/transition`, {
            method: "POST",
            body: JSON.stringify(["latency", "machineLoad"]),
            headers: {
            'Accept': 'application/json',
            'Content-Type': 'application/json'
            },
        })
    }).then(() => {
        window.location.reload()
    })
}

loadAndDraw();