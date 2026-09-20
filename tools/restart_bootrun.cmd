@echo off
rem 一键重启后端(bootRun),日志输出到项目根 bootrun-current.log
cd /d "d:\idea project\asean-weather-logistics"
gradlew.bat bootRun --console=plain > bootrun-current.log 2>&1
