@echo off
setlocal
set "ROOT=%~dp0"
set "JAVA_HOME=%ROOT%.tools\jdk\jdk-25.0.5+8"
set "DEPS_CLJ_TOOLS_DIR=%ROOT%.tools\clojure-tools"
set "CLJ_CONFIG=%ROOT%.config\clojure"
set "CLJ_CACHE=%ROOT%.cache\clojure"
"%ROOT%.tools\cli\deps.exe" -Srepro %*
exit /b %errorlevel%
