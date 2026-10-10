@echo off
rem 一键重启后端(bootRun),日志输出到项目根 bootrun-current.log
rem 工作目录由脚本自身位置推导，不写死开发机的绝对路径
cd /d "%~dp0.."
gradlew.bat bootRun --console=plain > bootrun-current.log 2>&1
