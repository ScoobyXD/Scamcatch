@echo off
setlocal
rem One-time setup so GitHub Actions can publish scamcatch.io. Uses only the AWS CLI, like Publish Website.cmd.
rem Deploys website\scamcatch\infra\github-publish.json as the scamcatch-github-publish stack and logs to github-publish-setup-log.txt.
cd /d "%~dp0"
set "LOG=website\scamcatch\infra\github-publish-setup-log.txt"
echo Setting up GitHub publishing for scamcatch.io...
echo Setup started %date% %time%> "%LOG%"
set "OIDC="
for /f "usebackq delims=" %%a in (`aws iam list-open-id-connect-providers --profile mangorts-dev --query "OpenIDConnectProviderList[?ends_with(Arn,'token.actions.githubusercontent.com')].Arn" --output text`) do set "OIDC=%%a"
if /i "%OIDC%"=="None" set "OIDC="
if defined OIDC (echo Reusing GitHub sign-in: %OIDC%) else (echo Creating GitHub sign-in for this AWS account.)
if defined OIDC (echo Reusing GitHub sign-in: %OIDC%>> "%LOG%") else (echo Creating GitHub sign-in.>> "%LOG%")
call aws cloudformation deploy --stack-name scamcatch-github-publish --template-file website\scamcatch\infra\github-publish.json --capabilities CAPABILITY_NAMED_IAM --no-fail-on-empty-changeset --parameter-overrides "ExistingOidcProviderArn=%OIDC%" --profile mangorts-dev --region us-east-1 >> "%LOG%" 2>&1
if errorlevel 1 goto failed
call aws cloudformation describe-stacks --stack-name scamcatch-github-publish --query "Stacks[0].Outputs[0].OutputValue" --output text --profile mangorts-dev --region us-east-1 >> "%LOG%" 2>&1
if errorlevel 1 goto failed
echo DONE>> "%LOG%"
type "%LOG%"
echo.
echo DONE. GitHub can now publish scamcatch.io. Tell Claude it finished.
pause
exit /b 0
:failed
echo STOPPED>> "%LOG%"
type "%LOG%"
echo.
echo STOPPED. Tell Claude; the reason is in %LOG%.
pause
exit /b 1
