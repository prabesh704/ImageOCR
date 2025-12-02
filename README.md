# iOCR ![Description Here](https://raw.githubusercontent.com/moacirrf/netbeans-markdown/main/images/nblogo48x48.png)

***

## Description
A simple GUI utility to act as a local (offline) image search engine.
Creates an index/db containing [imagePath, imageTextContent] pairs for easy searching.

## DB Struct

| cc | Row0 |  Row1 |
|----------|----------|-----------|
|  cc  |   imagePath  |   imageText(keywords)   |

## Features

- [x] Advanced indexing

***

## Setup
# Important!:
 Before usage ensure tesseract is installed and correctly configured on your system.
***
### Linux
Download the fat (with dependencies) jar, cd to your downloaded file
run ```java -jar filename.jar```#OR 
make it executable from GUI using the chmod command.

### Others (Win, Mac, Chrome etc)
Follow appropriate (common/normal) procedure of executing a jar, please use the fat jar.

# How To Use

Launch the program (a new Window [GUI] should appear).
Pick a folder to index (index persists across sessions).
Search for any image using the search bar (enter keyword(s) it contains).
Click on an image to expand view or double click to view in gallery.