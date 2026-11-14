@echo off
setlocal
pushd "%~dp0.."
if errorlevel 1 exit /b 1
call gradlew.bat --init-script "%~dp0s3.init.gradle" %*
set "SAFEPATH_BUILD_EXIT=%ERRORLEVEL%"
popd
exit /b %SAFEPATH_BUILD_EXIT%
