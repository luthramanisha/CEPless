#!/usr/bin/python

# File name: precision.py
# Author: Sebastian Hennig
# Date created: 18.07.2018
# Python Version: 2.7
# Description: Calculates the precision and accuracy values of the simulation executed

import csv
import sys

#with open(sys.argv[1], 'rb') as f:
 #   reader = csv.reader(f)
  #  filtered = list(reader)

with open(sys.argv[1], 'rb') as f:
    reader = csv.reader(f)
    messages = list(reader)

# Sanity check
# Total amount of messages has to be greater-equal to filtered messages
#assert(len(messages) >= len(filtered))

#pos = 0
tpos = 0
fpos = 0
#neg = 0
tneg = 0
fneg = 0
#all = len(messages) # number of events in total divided by the number of data producers

#for item in filtered:
 #   item_info = item[0].split(' \t ')
    #pos += 1 # every output line of messages.csv is an event emitted by the query
   # if int(item_info[0]) != int(item_info[1]):
       # tpos += 1 # only lines with not equal user IDs are true positives


with open(sys.argv[1]) as csv_file:
    csv_reader = csv.reader(csv_file, delimiter='\t')
    line = 0
    # CSV structure
    # timestamp, filtered, event1, event2
    for row in csv_reader:
        if line != 0:
            filtered = row[1]
            print(row)
            event1 = row[2]
            event2 = row[3]
            if event1 != event2 and int(filtered) == 0:
                fneg += 1
            elif event1 != event2 and int(filtered) == 1:
                tpos += 1
            elif event1 == event2 and int(filtered) == 1:
                fpos += 1
            else:
                tneg += 1
        line += 1

precision = float(tpos)/float(tpos + fpos)
accuracy = float((tpos + tneg)) / float(tpos + fpos + tneg + fneg)

print("Total events: " + str(tpos + fpos + tneg + fneg) +
      "\nPositives: " + str(tpos + fpos) +
      "\nTrue positives: " + str(tpos) +
      "\nTrue negatives: " + str(tneg) +
      "\n\nPrecision: " + str(precision) +
      "\nAccuracy: " + str(accuracy))