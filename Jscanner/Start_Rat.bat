start http://localhost:8080
@echo off
title RAT SWARM v2 - FULL PASSIVE WARDRIVING MODE
color 0A
echo.
echo  ██████╗  █████╗ ████████╗   ███████╗██╗    ██╗ █████╗ ██████╗ ███╗   ███╗
echo  ██╔══██╗██╔══██╗╚══██╔══╝   ██╔════╝██║    ██║██╔══██╗██╔══██╗████╗ ████║
echo  ██████╔╝███████║   ██║      ███████╗██║ █╗ ██║███████║██████╔╝██╔████╔██║
echo  ██╔══██╗██╔══██║   ██║      ╚════██║██║███╗██║██╔══██║██╔══██╗██║╚██╔╝██║
echo  ██║  ██║██║  ██║   ██║      ███████║╚███╔███╔╝██║  ██║██║  ██║██║ ╚═╝ ██║
echo  ╚═╝  ╚═╝╚═╝  ╚═╝   ╚═╝      ╚══════╝ ╚══╝╚══╝ ╚═╝  ╚═╝╚═╝  ╚═╝╚═╝     ╚═╝
echo.
echo                                  FULL PASSIVE MODE ENGAGED
echo                                   Dashboard: http://localhost:8080
echo.
echo  Starting the swarm in 3...
ping -n 1 127.0.0.1 >nul
echo  2...
ping -n 1 127.0.0.1 >nul
echo  1...
ping -n 1 127.0.0.1 >nul
echo  SWARM AWAKENED
echo.

"C:\Program Files\Java\jdk-24\bin\java.exe" -cp ".;pcap4j-core-1.8.2.jar;pcap4j-packetfactory-static-1.8.2.jar;jna-4.5.1.jar;slf4j-api-1.7.36.jar;slf4j-nop-1.7.36.jar" Rat 8080

echo.
echo Swarm terminated. Press any key to exit...
pause >nul