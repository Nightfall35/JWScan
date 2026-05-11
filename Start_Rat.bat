@echo off
set WIGLE_API_NAME=NightFall35
set WIGLE_API_TOKEN=0dd81d8f68114bc26317df8cf8a3c967
title RAT SWARM v2 - FULL PASSIVE WARDRIVING MODE
color 0A

cd /d "C:\Users\ishma\Desktop\Architect\Jscanner"

echo.
echo  FULL PASSIVE MODE ENGAGED
echo  Dashboard: http://localhost:8080
echo.
echo  Starting the swarm in 3...
ping -n 2 127.0.0.1 >nul
echo  2...
ping -n 2 127.0.0.1 >nul
echo  1...
ping -n 2 127.0.0.1 >nul
echo  SWARM AWAKENED
echo.

start "" "http://localhost:8080"

javac -encoding UTF-8 -cp "pcap4j-core-1.8.2.jar;pcap4j-packetfactory-static-1.8.2.jar;json-20210307.jar;jna-5.13.0.jar;jna-platform-5.13.0.jar;slf4j-api-1.7.36.jar;slf4j-simple-1.7.36.jar;openpdf-1.3.30.jar" *.java

echo.
echo  Swarm terminated. Press any key to exit...
pause >nul