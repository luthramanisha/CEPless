var reloadTimer = null
var algorithms; 

function getAlgorithms() {
    fetch(`http://${SERVER}:${GUI_PORT}/algorithms`).then(body => {
        console.log(body)
        return body.json()
    }).then((body) => {
        algorithms = body.algorithms
        // add the options to the select list
        var select = document.getElementById('algorithms');
        var selectModes = document.getElementById('transitionMode');

        var opt = document.createElement('option');
        opt.value = "none"
        opt.innerHTML = "Not set";
        select.appendChild(opt);

        opt = document.createElement('option');
        opt.value = "none"
        opt.innerHTML = "Not set";
        selectModes.appendChild(opt);

        for (let key in body.algorithms) {
            let algo = body.algorithms[key]

            let text = ""
            let i = 0

            for (let key in algo.optimizationCriteria) {
                let crit = algo.optimizationCriteria[key]
                if (i != 0) {
                    text += ", "
                }
                if (crit === 'messageHops') {
                    crit = 'messageOverhead';
                }
                text += crit
                i++
            }


            // Modify optimization criteria
            if (algo.algorithm === 'Relaxation') {
                text = 'BDP, machineLoad'
            } else if (algo.algorithm === 'MDCEP') {
                text = 'messageOverhead'
            } else if (algo.algorithm === 'GlobalOptimalBDPAlgorithm') {
                text = 'BDP'
            } else if (algo.algorithm === 'ProducerConsumer') {
                text = ''
                algo.optimizationCriteria = []
            }
            text += " (" + algo.algorithm + ")"

            var opt = document.createElement('option');
            opt.value = JSON.stringify(algo) //JSON.stringify(algo.optimizationCriteria);
            opt.innerHTML = text;
            select.appendChild(opt);
        }

        body.modes.forEach(m => {
            var opt = document.createElement('option');
            opt.value = m
            opt.innerHTML = m;
            selectModes.appendChild(opt);
        })

        getStatus()
    })
}

function getStatus() {
    fetch(`http://${SERVER}:${GUI_PORT}/status`).then(body => {
        console.log(body)
        return body.json()
    }).then((body) => {
        // add the options to the select list
        var select = document.getElementById('algorithms');
        var selectModes = document.getElementById('transitionMode');
        var selectedAlgorithm = algorithms.find(a => a.algorithm === body.placementStrategy);
        if (selectedAlgorithm) {
            select.value = JSON.stringify(selectedAlgorithm);
        }
        if (body.transitionMode === "none") {
            selectModes.value = "none"
        } else {
            selectModes.value = body.transitionMode
        }
    })
}

function start() {
    var e = document.getElementById("transitionMode");
    var e2 = document.getElementById("algorithms");
    var mode = e.options[e.selectedIndex].value;
    var criteria
    if (mode === "Not set") {
        criteria = ["BDP"]
    } else {
        criteria = JSON.parse(e2.options[e2.selectedIndex].value).optimizationCriteria;
    }
    

    fetch(`http://${SERVER}:${GUI_PORT}/start`, {
        method: "POST",
        body: JSON.stringify({mode, criteria}),
        headers: {
        'Accept': 'application/json',
        'Content-Type': 'application/json'
        },
    }).then((body) => {
        window.location.reload()
    })
}

function stop() {
    fetch(`http://${SERVER}:${GUI_PORT}/stop`, {
        method: "POST",
        body: JSON.stringify({}),
        headers: {
        'Accept': 'application/json',
        'Content-Type': 'application/json'
        },
    }).then((body) => {
        console.log('test')
        window.location.reload()
    })
}

function transition() {
    var e = document.getElementById("algorithms");
    var algorithm = JSON.parse(e.options[e.selectedIndex].value)

    if (algorithm.optimizationCriteria.length > 0) {
        fetch(`http://${SERVER}:${GUI_PORT}/transition`, {
            method: "POST",
            body: JSON.stringify(algorithm.optimizationCriteria),
            headers: {
            'Accept': 'application/json',
            'Content-Type': 'application/json'
            },
        }).then(() => {
            window.location.reload()
        })
    } else {
        fetch(`http://${SERVER}:${GUI_PORT}/manualTransition`, {
            method: "POST",
            body: JSON.stringify({ "algorithm": algorithm.algorithm }),
            headers: {
            'Accept': 'application/json',
            'Content-Type': 'application/json'
            },
        }).then(() => {
            window.location.reload()
        })
    }


}

function didChangeAutoReload() {
    console.log('called');
    var e = document.getElementById('auto-reload');
    if (e.checked) {
        reloadTimer = setTimeout(() => {
            window.location.reload()
        }, 3000)
    } else {
        clearTimeout(reloadTimer)
        reloadTimer = null
    }
    localStorage.setItem('auto-reload', e.checked)
}

function didChangeAutoTransitions() {
    var e = document.getElementById("auto-transition");
    fetch(`http://${SERVER}:${GUI_PORT}/autoTransition`, {
        method: "POST",
        body: {
            enabled: !!e.checked
        },
        headers: {
        'Accept': 'application/json',
        'Content-Type': 'application/json'
        },
    })
}

// 0 => normal view
// 1 => latency space view
function currentViewMode() {
    const urlParams = new URLSearchParams(window.location.search);
    const viewParam = urlParams.get('view');
    return viewParam === "latency" ? 1 : 0; 
}

function showLatencySpace() {
    const urlParams = new URLSearchParams(window.location.search);
    const viewParam = urlParams.get('view');
    if (currentViewMode() == 1) {
        window.location.href = window.location.href.replace('?view=latency', '');
        return;
    } else {
        window.location.href = window.location.href + "?view=latency"
        return;
    }
}

function didChangeHideLinkData() {
    var e = document.getElementById("hide-link-data");
    var elements = document.getElementsByClassName('link-data')
        Array.from(elements).forEach((ele) => {
            ele.style.display = e.checked ? 'none' : 'block'
        })
    localStorage.setItem('hide-link-data', e.checked)
}

getAlgorithms()

// set viewMode button text
var viewModeButton = document.getElementById('switchBtn');
if (currentViewMode() == 1) {
    viewModeButton.innerHTML = "Query View"
} else {
    viewModeButton.innerHTML = "Latency Space" 
}

let autoReload = localStorage.getItem('auto-reload')
if (autoReload === 'true') {
    didChangeAutoReload()
} else {
    var e = document.getElementById('auto-reload');
    e.checked = false
}

let hideLinkData = localStorage.getItem('hide-link-data')
var e = document.getElementById('hide-link-data');
if (hideLinkData === 'true') {
    e.checked = true;
    setTimeout(() => didChangeHideLinkData(), 100);
} else {
    e.checked = false
}